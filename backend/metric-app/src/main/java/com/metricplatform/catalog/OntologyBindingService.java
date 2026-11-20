package com.metricplatform.catalog;

import static com.metricplatform.catalog.BindingEvidence.*;

import static org.springframework.http.HttpStatus.*;

import com.metricplatform.catalog.AssetGrantRepository.Asset;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.ontology.OntologyProblem;
import com.metricplatform.ontology.OntologyStore;
import com.metricplatform.security.PlatformPrincipal;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@RequiredArgsConstructor
public class OntologyBindingService {
    private final DeployedAssets deployed;
    private final AssetGrantRepository assets;
    private final BuildGateway gateway;
    private final OntologyStore store;

    public record Dataset(UUID assetId, String name) {}

    public record Datasets(UUID buildId, List<Dataset> datasets) {}

    public record Column(String name, String declaredType) {}

    public record Columns(UUID buildId, UUID assetId, List<Column> columns) {}

    private record Deployment(UUID build, DeploymentKey target) {}

    private record Model(BindingEvidence evidence, Map<String, Column> columns) {}

    private record CheckedProperty(BindingEvidence evidence, String type) {}

    public Datasets datasets(Scope scope) {
        Deployment current = current(scope);
        if (current == null) return new Datasets(null, List.of());
        var names = new LinkedHashMap<UUID, Dataset>();
        for (Asset member : members(current)) {
            var item = new Dataset(member.assetId(), member.entry().queryName());
            if (names.putIfAbsent(item.assetId(), item) != null) throw problem(AMBIGUOUS_ASSET);
        }
        requireCurrent(scope, current.build());
        return new Datasets(current.build(), List.copyOf(names.values()));
    }

    public Columns columns(Scope scope, UUID assetId, UUID buildId) {
        var current = requireCurrent(scope, buildId);
        var model = model(current, members(current), assetId);
        if (model.evidence() != MATCHED) throw problem(model.evidence());
        requireCurrent(scope, buildId);
        return new Columns(buildId, assetId, List.copyOf(model.columns().values()));
    }

    private Deployment requireCurrent(Scope scope, UUID buildId) {
        if (buildId == null)
            throw new OntologyProblem(
                    UNPROCESSABLE_ENTITY, "SOURCE_BUILD_REQUIRED", "请选择当前部署构建的字段");
        var active = current(scope);
        if (active == null || !buildId.equals(active.build())) throw problem(DEPLOYMENT_CHANGED);
        return active;
    }

    private Deployment current(Scope scope) {
        TenantScope.require(scope.tenantId());
        Deployment active = null;
        for (var target : assets.targets()) {
            if (!scope.environmentId().equals(target.environmentId())) continue;
            UUID build;
            try {
                build = deployed.current(target);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == 410) throw problem(DEPLOYMENT_CHANGED);
                throw problem(UPSTREAM_UNAVAILABLE);
            } catch (BuildGateway.Failure | IllegalStateException ex) {
                throw problem(UPSTREAM_UNAVAILABLE);
            }
            if (build == null) continue;
            if (active != null) throw problem(AMBIGUOUS_DEPLOYMENT);
            active = new Deployment(build, target);
        }
        return active;
    }

    private List<Asset> members(Deployment current) {
        return assets.members(current.build(), PlatformPrincipal.current().identityId()).stream()
                .filter(
                        a ->
                                current.target().equals(a.target())
                                        && DeployedAssets.DATASET.equals(a.entry().kind()))
                .toList();
    }

    private Model model(Deployment current, List<Asset> members, UUID id) {
        var matches = members.stream().filter(a -> id.equals(a.assetId())).toList();
        if (matches.isEmpty()) return new Model(ASSET_NOT_DEPLOYED, Map.of());
        if (matches.size() > 1) return new Model(AMBIGUOUS_ASSET, Map.of());
        String node = matches.getFirst().entry().nodeId();
        try {
            var view = gateway.resource(current.build(), node);
            if (view == null
                    || !current.build().equals(view.buildId())
                    || !node.equals(view.resourceId())
                    || view.resource() == null
                    || !node.equals(view.resource().getOrDefault("unique_id", node))
                    || !"model".equals(view.resource().getOrDefault("resource_type", "model"))) {
                return new Model(UPSTREAM_UNAVAILABLE, Map.of());
            }
            var declarations = view.resource().get("columns");
            Map<String, Column> columns = new TreeMap<>();
            if (declarations != null && !(declarations instanceof Map<?, ?>))
                return new Model(UPSTREAM_UNAVAILABLE, Map.of());
            if (declarations instanceof Map<?, ?> declared) {
                for (var field : declared.entrySet()) {
                    if (!(field.getKey() instanceof String name)
                            || name.isBlank()
                            || !(field.getValue() instanceof Map<?, ?> metadata)
                            || metadata.get("name") != null && !name.equals(metadata.get("name"))) {
                        return new Model(UPSTREAM_UNAVAILABLE, Map.of());
                    }
                    var type = metadata.get("data_type");
                    columns.put(
                            name,
                            new Column(
                                    name,
                                    type instanceof String text && !text.isBlank()
                                            ? text.strip()
                                            : null));
                }
            }
            return new Model(MATCHED, columns);
        } catch (BuildGateway.Failure ex) {
            return new Model(UPSTREAM_UNAVAILABLE, Map.of());
        }
    }

    public BindingReport check(Scope scope, Topology graph) {
        Deployment active;
        try {
            active = current(scope);
        } catch (OntologyProblem ex) {
            return unavailableReport(scope, graph, BindingEvidence.valueOf(ex.code()));
        }
        BindingReport report;
        if (active == null) {
            report = unavailableReport(scope, graph, NO_DEPLOYMENT);
        } else {
            var cached =
                    store.readReport(scope, active.build(), graph.revision())
                            .filter(
                                    previous ->
                                            previous.findings().stream()
                                                    .noneMatch(
                                                            f ->
                                                                    UPSTREAM_UNAVAILABLE
                                                                            .name()
                                                                            .equals(f.reason())));
            report =
                    cached.orElseGet(
                            () ->
                                    saveReport(
                                            scope,
                                            new BindingReport(
                                                    active.build(),
                                                    graph.revision(),
                                                    findings(graph, active))));
        }
        Deployment latest;
        try {
            // 图写锁可能等待其他编辑，返回复核结果前再确认活动指针。
            latest = current(scope);
        } catch (OntologyProblem ex) {
            return unavailableReport(scope, graph, BindingEvidence.valueOf(ex.code()));
        }
        return Objects.equals(active, latest)
                ? report
                : unavailableReport(scope, graph, DEPLOYMENT_CHANGED);
    }

    private BindingReport unavailableReport(Scope scope, Topology graph, BindingEvidence reason) {
        return saveReport(
                scope,
                new BindingReport(null, graph.revision(), unavailable(scope, graph, reason)));
    }

    private BindingReport saveReport(Scope scope, BindingReport report) {
        if (!store.saveReport(scope, report)) throw revisionConflict();
        return report;
    }

    private List<Finding> findings(Topology graph, Deployment active) {
        var candidates = members(active);
        var models = new HashMap<UUID, Model>();
        var properties = new HashMap<UUID, CheckedProperty>();
        var findings = new ArrayList<Finding>();
        for (var object : graph.objects()) {
            var model =
                    models.computeIfAbsent(
                            object.datasetAssetId(), id -> model(active, candidates, id));
            findings.add(model.evidence().finding("OBJECT", object.id()));
            for (var property : object.properties()) {
                var checked = checkProperty(model, property);
                properties.put(property.id(), checked);
                findings.add(checked.evidence().finding("PROPERTY", property.id()));
            }
        }
        for (var relation : graph.relations()) {
            var source = properties.get(relation.sourcePropertyId());
            var target = properties.get(relation.targetPropertyId());
            var evidence = BindingEvidence.combine(source.evidence(), target.evidence());
            // 即使某个旧类型未知，当前两端明确不兼容时仍要求修复。
            var compared = BindingEvidence.compareTypes(source.type(), target.type());
            findings.add(
                    BindingEvidence.combine(evidence, compared).finding("RELATION", relation.id()));
        }
        return findings;
    }

    private static CheckedProperty checkProperty(Model model, Property property) {
        if (model.evidence() != MATCHED) return new CheckedProperty(model.evidence(), null);
        var column = model.columns().get(property.columnName());
        if (column == null) return new CheckedProperty(COLUMN_UNDECLARED, null);
        String type = column.declaredType();
        var evidence =
                property.declaredType() == null && type != null
                        ? MATCHED
                        : BindingEvidence.compareTypes(property.declaredType(), type);
        return new CheckedProperty(evidence, type);
    }

    private static List<Finding> unavailable(Scope scope, Topology graph, BindingEvidence reason) {
        var findings = new ArrayList<Finding>();
        findings.add(reason.finding("ENVIRONMENT", scope.environmentId()));
        for (var object : graph.objects()) {
            findings.add(reason.finding("OBJECT", object.id()));
            for (var p : object.properties()) findings.add(reason.finding("PROPERTY", p.id()));
        }
        for (var relation : graph.relations())
            findings.add(reason.finding("RELATION", relation.id()));
        return findings;
    }

    /** 仅重新核实新增或改绑的属性，允许在旧字段无法核实的情况下修复其他字段。 */
    public ObjectType bindObject(Scope scope, UUID buildId, ObjectType object, Set<UUID> changed) {
        var candidates = columns(scope, object.datasetAssetId(), buildId);
        var byName = new HashMap<String, Column>();
        for (var column : candidates.columns()) byName.put(column.name(), column);
        List<Property> verified = new ArrayList<>();
        for (var property : object.properties()) {
            if (!changed.contains(property.id())) {
                verified.add(property);
                continue;
            }
            var column = byName.get(property.columnName());
            if (column == null) throw problem(COLUMN_UNDECLARED);
            verified.add(
                    new Property(
                            property.id(), property.name(), column.name(), column.declaredType()));
        }
        return new ObjectType(
                object.id(),
                object.name(),
                object.description(),
                object.datasetAssetId(),
                object.primaryPropertyId(),
                verified);
    }

    public void bindRelation(
            Scope scope,
            UUID buildId,
            ObjectType source,
            Property from,
            ObjectType target,
            Property to) {
        var active = requireCurrent(scope, buildId);
        var candidates = members(active);
        var first = model(active, candidates, source.datasetAssetId());
        var second =
                source.datasetAssetId().equals(target.datasetAssetId())
                        ? first
                        : model(active, candidates, target.datasetAssetId());
        var left = checkProperty(first, from);
        var right = checkProperty(second, to);
        for (var endpoint : List.of(left, right)) {
            if (endpoint.evidence() != MATCHED && endpoint.evidence() != TYPE_UNKNOWN)
                throw problem(endpoint.evidence());
        }
        if (BindingEvidence.compareTypes(left.type(), right.type()) == TYPE_CONFLICT)
            throw problem(TYPE_CONFLICT);
        requireCurrent(scope, buildId);
    }

    public static OntologyProblem revisionConflict() {
        return new OntologyProblem(CONFLICT, "REVISION_CONFLICT", "本体已被修改，请刷新后重试");
    }

    private static OntologyProblem problem(BindingEvidence reason) {
        return switch (reason) {
            case AMBIGUOUS_DEPLOYMENT ->
                    new OntologyProblem(CONFLICT, reason.name(), "环境有多个活动部署目标");
            case DEPLOYMENT_CHANGED ->
                    new OntologyProblem(CONFLICT, reason.name(), "部署已切换，请刷新候选字段");
            case AMBIGUOUS_ASSET ->
                    new OntologyProblem(UNPROCESSABLE_ENTITY, reason.name(), "当前构建的数据集匹配不唯一");
            case ASSET_NOT_DEPLOYED ->
                    new OntologyProblem(NOT_FOUND, reason.name(), "此环境的当前部署中没有该数据集");
            case COLUMN_UNDECLARED ->
                    new OntologyProblem(UNPROCESSABLE_ENTITY, reason.name(), "当前构建未声明选定的字段");
            case TYPE_CONFLICT ->
                    new OntologyProblem(UNPROCESSABLE_ENTITY, reason.name(), "声明的字段类型不兼容");
            default ->
                    new OntologyProblem(
                            SERVICE_UNAVAILABLE, UPSTREAM_UNAVAILABLE.name(), "当前部署的元数据暂不可用");
        };
    }
}
