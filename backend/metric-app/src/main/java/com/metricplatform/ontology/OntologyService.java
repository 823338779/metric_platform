package com.metricplatform.ontology;

import static org.springframework.http.HttpStatus.*;

import com.metricplatform.catalog.OntologyBindingService;
import com.metricplatform.catalog.OntologyBindingService.Columns;
import com.metricplatform.catalog.OntologyBindingService.Datasets;
import com.metricplatform.environment.EnvironmentService;
import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OntologyService {
    private final EnvironmentService environments;
    private final OntologyStore store;
    private final OntologyBindingService bindings;

    public record View(
            long revision,
            List<ObjectType> objects,
            List<Relation> relations,
            BindingReport bindingReport) {}

    public record ObjectWrite(Long expectedRevision, UUID sourceBuildId, ObjectType object) {}

    public record RelationWrite(Long expectedRevision, UUID sourceBuildId, Relation relation) {}

    public record Saved(UUID id, long revision) {}

    private Scope scope(UUID environmentId) {
        var principal = PlatformPrincipal.current();
        principal.requireManager();
        environments.get(environmentId);
        return new Scope(principal.tenantId(), environmentId);
    }

    public View read(UUID environmentId) {
        var scope = scope(environmentId);
        var graph = store.read(scope);
        return new View(
                graph.revision(), graph.objects(), graph.relations(), bindings.check(scope, graph));
    }

    public Datasets datasets(UUID environmentId) {
        return bindings.datasets(scope(environmentId));
    }

    public Columns columns(UUID environmentId, UUID assetId, UUID buildId) {
        return bindings.columns(scope(environmentId), assetId, buildId);
    }

    private Topology atRevision(Scope scope, Long expected) {
        if (expected == null || expected < 0) throw invalid("必须提供有效的编辑序号");
        var graph = store.read(scope);
        if (expected != graph.revision()) throw OntologyBindingService.revisionConflict();
        return graph;
    }

    public Saved saveObject(UUID environmentId, UUID pathId, ObjectWrite request) {
        var scope = scope(environmentId);
        var graph = atRevision(scope, request.expectedRevision());
        var object = request.object();
        if (object == null
                || object.id() == null
                || object.datasetAssetId() == null
                || object.properties() == null
                || pathId != null && !pathId.equals(object.id())) throw invalid("对象、属性或数据集无效");
        var original =
                graph.objects().stream()
                        .filter(value -> value.id().equals(object.id()))
                        .findFirst()
                        .orElse(null);
        checkTarget(pathId, original);
        var previous = new HashMap<UUID, Property>();
        if (original != null && original.datasetAssetId().equals(object.datasetAssetId())) {
            for (var p : original.properties()) previous.put(p.id(), p);
        }
        var changed = new HashSet<UUID>();
        boolean primaryChanged =
                original != null
                        && !original.primaryPropertyId().equals(object.primaryPropertyId());
        var properties = new ArrayList<Property>();
        for (var property : object.properties()) {
            if (property == null
                    || property.id() == null
                    || property.columnName() == null
                    || property.columnName().isBlank()) throw invalid("属性必须带有 ID 和绑定字段");
            var old = previous.get(property.id());
            if (old != null
                    && old.columnName().equals(property.columnName())
                    && property.declaredType() == null) {
                property =
                        new Property(
                                property.id(),
                                property.name(),
                                property.columnName(),
                                old.declaredType());
            }
            properties.add(property);
            if (old == null
                    || !old.columnName().equals(property.columnName())
                    || !Objects.equals(old.declaredType(), property.declaredType())
                    || primaryChanged && property.id().equals(object.primaryPropertyId()))
                changed.add(property.id());
        }
        var normalized =
                new ObjectType(
                        object.id(),
                        object.name(),
                        object.description(),
                        object.datasetAssetId(),
                        object.primaryPropertyId(),
                        properties);
        if (original != null) OntologyReferences.checkUpdate(graph, original, normalized);
        var verified =
                changed.isEmpty()
                        ? normalized
                        : bindings.bindObject(scope, request.sourceBuildId(), normalized, changed);
        long revision;
        if (pathId == null) {
            revision =
                    store.createObject(
                            scope, graph.revision(), verified, request.sourceBuildId(), changed);
        } else if (!changed.isEmpty()) {
            revision =
                    store.updateObject(
                            scope, graph.revision(), verified, request.sourceBuildId(), changed);
        } else {
            revision = store.updateObject(scope, graph.revision(), verified);
        }
        return new Saved(object.id(), revision);
    }

    public Saved saveRelation(UUID environmentId, UUID pathId, RelationWrite request) {
        var scope = scope(environmentId);
        var graph = atRevision(scope, request.expectedRevision());
        var relation = request.relation();
        if (relation == null
                || relation.id() == null
                || pathId != null && !pathId.equals(relation.id())) throw invalid("关系 ID 无效");
        var previous =
                graph.relations().stream()
                        .filter(r -> r.id().equals(relation.id()))
                        .findFirst()
                        .orElse(null);
        checkTarget(pathId, previous);
        boolean bindingChanged = previous == null || !sameEndpoints(previous, relation);
        if (bindingChanged) {
            var source = object(graph, relation.sourceObjectId());
            var target = object(graph, relation.targetObjectId());
            bindings.bindRelation(
                    scope,
                    request.sourceBuildId(),
                    source,
                    property(source, relation.sourcePropertyId()),
                    target,
                    property(target, relation.targetPropertyId()));
        }
        long revision;
        if (previous == null) {
            revision =
                    store.createRelation(
                            scope, graph.revision(), relation, request.sourceBuildId());
        } else if (bindingChanged) {
            revision =
                    store.updateRelation(
                            scope, graph.revision(), relation, request.sourceBuildId());
        } else {
            revision = store.updateRelation(scope, graph.revision(), relation);
        }
        return new Saved(relation.id(), revision);
    }

    public Saved deleteObject(UUID environmentId, UUID id, long expectedRevision) {
        return new Saved(id, store.deleteObject(scope(environmentId), expectedRevision, id));
    }

    public Saved deleteRelation(UUID environmentId, UUID id, long expectedRevision) {
        return new Saved(id, store.deleteRelation(scope(environmentId), expectedRevision, id));
    }

    private static void checkTarget(UUID pathId, Object previous) {
        if (pathId != null && previous == null) throw missing();
        if (pathId == null && previous != null)
            throw new OntologyProblem(CONFLICT, "ID_CONFLICT", "此 ID 已存在");
    }

    private static boolean sameEndpoints(Relation first, Relation second) {
        return first.sourceObjectId().equals(second.sourceObjectId())
                && first.targetObjectId().equals(second.targetObjectId())
                && first.sourcePropertyId().equals(second.sourcePropertyId())
                && first.targetPropertyId().equals(second.targetPropertyId());
    }

    private static ObjectType object(Topology graph, UUID id) {
        return graph.objects().stream()
                .filter(o -> o.id().equals(id))
                .findFirst()
                .orElseThrow(OntologyService::missing);
    }

    private static Property property(ObjectType owner, UUID id) {
        return owner.properties().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> invalid("选定属性不属于此端点对象"));
    }

    private static OntologyProblem missing() {
        return new OntologyProblem(NOT_FOUND, "NOT_FOUND", "此环境中无此资源");
    }

    private static OntologyProblem invalid(String detail) {
        return new OntologyProblem(UNPROCESSABLE_ENTITY, "INVALID", detail);
    }
}
