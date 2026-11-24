package com.metricplatform.catalog;

import static com.metricplatform.catalog.BindingEvidence.*;
import static com.metricplatform.catalog.BindingEvidence.valueOf;
import static org.springframework.http.HttpStatus.*;

import com.metricplatform.catalog.AssetGrantRepository.Asset;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.environment.EnvironmentContracts.BuildExecutionSnapshot;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.ontology.OntologyProblem;
import com.metricplatform.ontology.OntologyStore;
import com.metricplatform.ontology.WarehouseColumns;
import com.metricplatform.ontology.WarehouseColumns.*;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@EnableConfigurationProperties(WarehouseMetadataPolicy.class)
public class OntologyBindingService {
    private final DeployedAssets deployed;
    private final AssetGrantRepository assets;
    private final BuildGateway gateway;
    private final OntologyStore store;

    private final WarehouseMetadataPolicy policy;
    private final WarehouseColumns reader;
    private static final String WAREHOUSE = "WAREHOUSE", DECLARATION = "DECLARATION", NONE = "NONE";
    private static final String PROPERTY = "PROPERTY", RELATION = "RELATION";

    @Autowired
    public OntologyBindingService(
            DeployedAssets deployed,
            AssetGrantRepository assets,
            BuildGateway gateway,
            OntologyStore store,
            WarehouseMetadataPolicy policy,
            WarehouseColumns reader) {
        this.deployed = deployed;
        this.assets = assets;
        this.gateway = gateway;
        this.store = store;
        this.policy = policy;
        this.reader = reader;
    }

    public OntologyBindingService(
            DeployedAssets deployed,
            AssetGrantRepository assets,
            BuildGateway gateway,
            OntologyStore store) {
        this(deployed, assets, gateway, store, new WarehouseMetadataPolicy(List.of()), null);
    }

    /** 同一操作共享快照和表清单，避免自关联、多条边重复查询。 */
    private final class Operation {
        final Scope scope;
        final Deployment deployment;
        final Map<TableTarget, TableInventory> tables = new HashMap<>();
        final Map<UUID, EvidenceUpdate> updates = new LinkedHashMap<>();
        final long sequence;
        boolean loaded;
        BuildExecutionSnapshot snapshot;

        Operation(Scope scope, Deployment deployment, boolean writing) {
            this.scope = scope;
            this.deployment = deployment;
            this.sequence = writing && policy.active() ? store.beginObservation(scope) : 0;
        }

        ObservationWrite write() {
            return new ObservationWrite(
                    sequence,
                    List.copyOf(updates.values()),
                    () -> assertCurrent(scope, deployment));
        }

        WarehouseMetadataPolicy.Resolution resolve(Map<String, ?> resource) {
            if (!loaded) {
                var detail = gateway.getBuild(deployment.build());
                if (detail != null && deployment.build().equals(detail.buildId()))
                    snapshot = detail.execution();
                loaded = true;
            }
            return policy.resolve(scope.tenantId(), scope.environmentId(), snapshot, resource);
        }
    }

    public boolean physicalEnabled() {
        return policy.active();
    }

    public record Dataset(UUID assetId, String name) {}

    public record Datasets(UUID buildId, List<Dataset> datasets) {}

    public record Column(
            String name,
            String declaredType,
            String typeSource,
            ColumnObservation lastObservation,
            String reason) {
        public Column(String name, String declaredType) {
            this(name, declaredType, declaredType == null ? NONE : DECLARATION, null, null);
        }
    }

    public record Columns(UUID buildId, UUID assetId, List<Column> columns) {}

    private record Deployment(UUID build, DeploymentKey target, long version) {}

    private record Model(
            BindingEvidence evidence,
            Map<String, Column> columns,
            WarehouseMetadataPolicy.Resolution resolution) {
        Model(BindingEvidence evidence, Map<String, Column> columns) {
            this(evidence, columns, null);
        }
    }

    private record CheckedProperty(
            BindingEvidence evidence,
            String type,
            EndpointEvidence detail,
            PropertyEvidence value) {}

    public record VerifiedObject(ObjectType object, ObservationWrite observation) {}

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
        var op = new Operation(scope, current, false);
        var model = observe(op, model(op, members(current), assetId));
        if (model.evidence() != MATCHED) throw problem(model.evidence());
        assertCurrent(scope, current);
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

    private void assertCurrent(Scope scope, Deployment expected) {
        if (!expected.equals(current(scope))) throw problem(DEPLOYMENT_CHANGED);
    }

    private Deployment current(Scope scope) {
        TenantScope.require(scope.tenantId());
        Deployment active = null;
        for (var target : assets.targets()) {
            if (!scope.environmentId().equals(target.environmentId())) continue;
            UUID build;
            long version = 0;
            try {
                if (policy.active()) {
                    var pointer = deployed.currentPointer(target);
                    if (pointer == null) continue;
                    build = pointer.activeBuildId();
                    version = pointer.version();
                } else build = deployed.current(target);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == 410) throw problem(DEPLOYMENT_CHANGED);
                throw problem(UPSTREAM_UNAVAILABLE);
            } catch (BuildGateway.Failure ex) {
                throw problem(failureReason(ex));
            } catch (IllegalStateException ex) {
                throw problem(UPSTREAM_UNAVAILABLE);
            }
            if (build == null) continue;
            if (active != null) throw problem(AMBIGUOUS_DEPLOYMENT);
            active = new Deployment(build, target, version);
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

    private Model model(Operation op, List<Asset> members, UUID id) {
        var current = op.deployment;
        try {
            var matches = members.stream().filter(a -> id.equals(a.assetId())).toList();
            if (matches.isEmpty()) {
                var name = assets.datasetName(current.target().environmentId(), id);
                if (name.isPresent()) {
                    var catalog = gateway.catalog(current.build());
                    int matching = 0;
                    for (var node : catalog) {
                        if ("model".equals(node.path("resource_type").asText())
                                && name.get().equals(node.path("name").asText())
                                && ++matching > 1) return new Model(AMBIGUOUS_ASSET, Map.of());
                    }
                }
                return new Model(ASSET_NOT_DEPLOYED, Map.of());
            }
            if (matches.size() > 1) return new Model(AMBIGUOUS_ASSET, Map.of());
            String node = matches.getFirst().entry().nodeId();
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
            if (policy.active()) {
                if (!node.equals(view.resource().get("unique_id"))
                        || !"model".equals(view.resource().get("resource_type")))
                    return new Model(TARGET_UNRESOLVED, Map.of());
                var resolution = op.resolve(view.resource());
                if (resolution.target() != null || resolution.reason() != null) {
                    return new Model(MATCHED, columns, resolution);
                }
            }
            return new Model(MATCHED, columns);
        } catch (BuildGateway.Failure ex) {
            return new Model(failureReason(ex), Map.of());
        }
    }

    private Model observe(Operation op, Model model) {
        var resolution = model.resolution();
        if (resolution == null) return model;
        var target = resolution.target();
        var inventory =
                target == null
                        ? TableInventory.unavailable(resolution.reason())
                        : op.tables.computeIfAbsent(
                                target, t -> reader.read(t, resolution.access()));
        var columns = new TreeMap<String, Column>();
        model.columns()
                .forEach(
                        (name, column) ->
                                columns.put(
                                        name,
                                        observedColumn(
                                                column, inventory, target, op.deployment.build())));
        return new Model(MATCHED, columns, resolution);
    }

    private static void requireAuthorized(Model model) {
        if (model.evidence() != MATCHED) throw problem(model.evidence());
        if (model.resolution() != null && model.resolution().reason() != null) {
            var reason = valueOf(model.resolution().reason());
            if (!reason.temporary()) throw problem(reason);
        }
    }

    private static Column observedColumn(
            Column column, TableInventory inventory, TableTarget target, UUID build) {
        String actual = inventory.types().get(column.name());
        if (actual == null || actual.isBlank()) {
            String reason =
                    inventory.reason() != null
                            ? inventory.reason()
                            : inventory.complete() && !inventory.types().containsKey(column.name())
                                    ? COLUMN_MISSING.name()
                                    : METADATA_INVISIBLE.name();
            return new Column(column.name(), column.declaredType(), NONE, null, reason);
        }
        var observed = new ColumnObservation(actual, inventory.observedAt(), build, target);
        var check = compareOptional(column.declaredType(), actual);
        return new Column(column.name(), column.declaredType(), WAREHOUSE, observed, check.name());
    }

    private static BindingEvidence compareOptional(String expected, String actual) {
        return expected == null ? MATCHED : BindingEvidence.compareTypes(expected, actual);
    }

    private static BindingEvidence failureReason(BuildGateway.Failure ex) {
        return SERVICE_AUTHENTICATION_FAILED.name().equals(ex.error().code())
                ? SERVICE_AUTHENTICATION_FAILED
                : UPSTREAM_UNAVAILABLE;
    }

    public BindingReport check(Scope scope, Topology graph) {
        return check(scope, graph, false);
    }

    public BindingReport check(Scope scope, Topology graph, boolean refresh) {
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
                    (refresh
                                    ? Optional.<BindingReport>empty()
                                    : currentReport(scope, active, graph.revision()))
                            .filter(OntologyBindingService::canReuse);
            report =
                    cached.orElseGet(
                            () -> {
                                var op = new Operation(scope, active, true);
                                var result =
                                        new BindingReport(
                                                active.build(),
                                                graph.revision(),
                                                findings(graph, op),
                                                2,
                                                policy.version(),
                                                active.version());
                                if (!policy.active()) return saveReport(scope, result);
                                try {
                                    if (store.saveObservedReport(scope, result, op.write()))
                                        return result;
                                } catch (com.metricplatform.ontology.OntologyException ex) {
                                    if (ex.code()
                                            != com.metricplatform.ontology.OntologyException.Code
                                                    .CONFLICT) throw ex;
                                }
                                // 迟到的观察只返回相同部署、图版本和策略下已提交的胜出报告。
                                return currentReport(scope, active, graph.revision())
                                        .orElseThrow(OntologyBindingService::revisionConflict);
                            });
            if (policy.active() && store.read(scope).revision() != graph.revision())
                throw revisionConflict();
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

    private Optional<BindingReport> currentReport(Scope scope, Deployment active, long revision) {
        return store.readReport(scope, active.build(), revision)
                .filter(
                        report ->
                                report.formatVersion() == 2
                                        && report.deploymentVersion() == active.version()
                                        && policy.version().equals(report.policyVersion()));
    }

    private static boolean canReuse(BindingReport report) {
        return report.findings().stream()
                .noneMatch(
                        f ->
                                transientReason(f.reason())
                                        || UPSTREAM_UNAVAILABLE.name().equals(f.reason())
                                        || SERVICE_AUTHENTICATION_FAILED.name().equals(f.reason()));
    }

    private static boolean transientReason(String reason) {
        return Arrays.stream(BindingEvidence.values())
                .anyMatch(r -> r.name().equals(reason) && r.temporary());
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

    private List<Finding> findings(Topology graph, Operation op) {
        var candidates = members(op.deployment);
        var models = new HashMap<UUID, Model>();
        var properties = new HashMap<UUID, CheckedProperty>();
        var findings = new ArrayList<Finding>();
        for (var object : graph.objects()) {
            var model =
                    models.computeIfAbsent(
                            object.datasetAssetId(), id -> observe(op, model(op, candidates, id)));
            findings.add(model.evidence().finding("OBJECT", object.id()));
            for (var property : object.properties()) {
                var checked = checkProperty(model, property);
                properties.put(property.id(), checked);
                remember(op, property, checked);
                findings.add(
                        new Finding(
                                PROPERTY,
                                property.id(),
                                checked.evidence().status,
                                checked.evidence().name(),
                                checked.detail(),
                                null,
                                null));
            }
        }
        for (var relation : graph.relations()) {
            var source = properties.get(relation.sourcePropertyId());
            var target = properties.get(relation.targetPropertyId());
            var evidence = BindingEvidence.combine(source.evidence(), target.evidence());
            // 即使某个旧类型未知，当前两端明确不兼容时仍要求修复。
            var compared = BindingEvidence.compareTypes(source.type(), target.type());
            var combined = BindingEvidence.combine(evidence, compared);
            findings.add(
                    new Finding(
                            RELATION,
                            relation.id(),
                            combined.status,
                            combined.name(),
                            null,
                            source.detail(),
                            target.detail()));
        }
        return findings;
    }

    private static CheckedProperty checkProperty(Model model, Property property) {
        var column = model.columns().get(property.columnName());
        var previous = property.evidence();
        var baseline = previous == null ? null : previous.acceptedPhysicalType();
        var history = previous == null ? null : previous.lastObservation();
        var knownConflict = previous == null ? null : previous.knownConflict();
        String declared = column == null ? null : column.declaredType();
        String type = declared;
        BindingEvidence result;
        var observation = column == null ? null : column.lastObservation();
        if (model.evidence() != MATCHED) result = model.evidence();
        else if (column == null) result = COLUMN_UNDECLARED;
        else if (column.reason() == null) {
            result = type == null ? TYPE_UNKNOWN : compareOptional(property.declaredType(), type);
        } else {
            result = valueOf(column.reason());
            if (observation != null) {
                type = observation.physicalType();
                history = observation;
                result = combine(result, compareOptional(baseline, type));
                if (baseline == null && result == MATCHED) baseline = type;
            }
        }
        if (column != null
                && column.reason() != null
                && (observation != null || result == COLUMN_MISSING))
            knownConflict = result.status == BindingStatus.NEEDS_REPAIR ? result.name() : null;
        var value = new PropertyEvidence(baseline, history, result.name(), knownConflict);
        boolean physical = previous != null || column != null && column.reason() != null;
        var detail =
                physical
                        ? new EndpointEvidence(
                                property.columnName(),
                                declared,
                                column == null ? NONE : column.typeSource(),
                                baseline,
                                history,
                                result.name())
                        : null;
        return new CheckedProperty(result, type, detail, physical ? value : null);
    }

    private static void remember(Operation op, Property property, CheckedProperty checked) {
        if (checked.value() != null)
            op.updates.put(
                    property.id(),
                    new EvidenceUpdate(property.id(), property.evidence(), checked.value()));
    }

    private static void allowBinding(CheckedProperty checked) {
        var reason = checked.evidence();
        if (reason == MATCHED || reason == TYPE_UNKNOWN || reason.temporary()) {
            if (reason.temporary()
                    && checked.value() != null
                    && checked.value().knownConflict() != null)
                throw problem(valueOf(checked.value().knownConflict()));
            return;
        }
        throw problem(reason);
    }

    private static List<Finding> unavailable(Scope scope, Topology graph, BindingEvidence reason) {
        var findings = new ArrayList<Finding>();
        var endpoints = new HashMap<UUID, EndpointEvidence>();
        findings.add(reason.finding("ENVIRONMENT", scope.environmentId()));
        for (var object : graph.objects()) {
            findings.add(reason.finding("OBJECT", object.id()));
            for (var p : object.properties()) {
                var saved = p.evidence();
                var detail =
                        saved == null
                                ? null
                                : new EndpointEvidence(
                                        p.columnName(),
                                        p.declaredType(),
                                        NONE,
                                        saved.acceptedPhysicalType(),
                                        saved.lastObservation(),
                                        reason.name());
                endpoints.put(p.id(), detail);
                findings.add(
                        new Finding(
                                PROPERTY,
                                p.id(),
                                reason.status,
                                reason.name(),
                                detail,
                                null,
                                null));
            }
        }
        for (var r : graph.relations())
            findings.add(
                    new Finding(
                            RELATION,
                            r.id(),
                            reason.status,
                            reason.name(),
                            null,
                            endpoints.get(r.sourcePropertyId()),
                            endpoints.get(r.targetPropertyId())));
        return findings;
    }

    /** 仅重新核实新增或改绑的属性，允许在旧字段无法核实的情况下修复其他字段。 */
    public ObjectType bindObject(Scope scope, UUID buildId, ObjectType object, Set<UUID> changed) {
        return verifyObject(scope, buildId, object, changed, List.of()).object();
    }

    public VerifiedObject verifyObject(
            Scope scope,
            UUID buildId,
            ObjectType object,
            Set<UUID> changed,
            List<TypeAcceptance> accepts) {
        var op = new Operation(scope, requireCurrent(scope, buildId), true);
        var declared = model(op, members(op.deployment), object.datasetAssetId());
        requireAuthorized(declared);
        var model = observe(op, declared);
        var pending = new HashMap<UUID, TypeAcceptance>();
        for (var a : accepts) {
            if (a == null
                    || a.propertyId() == null
                    || a.expectedBaselineType() == null
                    || a.observedType() == null
                    || pending.put(a.propertyId(), a) != null) throw revisionConflict();
        }
        var verified = new ArrayList<Property>();
        for (var property : object.properties()) {
            var accept = pending.remove(property.id());
            if (accept == null && !changed.contains(property.id())) {
                verified.add(property);
                continue;
            }
            var column = model.columns().get(property.columnName());
            if (column == null) throw problem(COLUMN_UNDECLARED);
            if (TARGET_NOT_ALLOWED.name().equals(column.reason()))
                throw problem(TARGET_NOT_ALLOWED);
            // 绑定类型从部署模型取得，客户端声明与客户端实测值都不能替代服务端证据。
            var bound =
                    new Property(
                            property.id(),
                            property.name(),
                            column.name(),
                            column.declaredType(),
                            property.evidence());
            var checked = checkProperty(model, bound);
            if (accept != null) {
                var old = property.evidence();
                var actual = column.lastObservation();
                if (old == null
                        || actual == null
                        || !Objects.equals(
                                accept.expectedBaselineType(), old.acceptedPhysicalType())
                        || !accept.observedType().equals(actual.physicalType())
                        || !MATCHED.name().equals(column.reason())) throw revisionConflict();
                op.updates.put(
                        property.id(),
                        new EvidenceUpdate(
                                property.id(),
                                old,
                                new PropertyEvidence(
                                        actual.physicalType(), actual, MATCHED.name())));
            } else {
                allowBinding(checked);
                remember(op, bound, checked);
            }
            verified.add(accept == null ? bound : property);
        }
        if (!pending.isEmpty()) throw revisionConflict();
        assertCurrent(scope, op.deployment);
        return new VerifiedObject(
                new ObjectType(
                        object.id(),
                        object.name(),
                        object.description(),
                        object.datasetAssetId(),
                        object.primaryPropertyId(),
                        verified),
                policy.active() ? op.write() : null);
    }

    public ObservationWrite bindRelation(
            Scope scope,
            UUID buildId,
            ObjectType source,
            Property from,
            ObjectType target,
            Property to) {
        var op = new Operation(scope, requireCurrent(scope, buildId), true);
        var candidates = members(op.deployment);
        var first = model(op, candidates, source.datasetAssetId());
        var second =
                source.datasetAssetId().equals(target.datasetAssetId())
                        ? first
                        : model(op, candidates, target.datasetAssetId());
        requireAuthorized(first);
        requireAuthorized(second);
        var left = checkProperty(observe(op, first), from);
        var right = checkProperty(observe(op, second), to);
        if (left.evidence() == TARGET_NOT_ALLOWED || right.evidence() == TARGET_NOT_ALLOWED)
            throw problem(TARGET_NOT_ALLOWED);
        allowBinding(left);
        allowBinding(right);
        if (BindingEvidence.compareTypes(left.type(), right.type()) == TYPE_CONFLICT)
            throw problem(TYPE_CONFLICT);
        remember(op, from, left);
        remember(op, to, right);
        assertCurrent(scope, op.deployment);
        return policy.active() ? op.write() : null;
    }

    public static OntologyProblem revisionConflict() {
        return new OntologyProblem(CONFLICT, "REVISION_CONFLICT", "本体已被修改，请刷新后重试");
    }

    private static OntologyProblem problem(BindingEvidence reason) {
        return switch (reason) {
            case SERVICE_AUTHENTICATION_FAILED ->
                    new OntologyProblem(
                            BAD_GATEWAY, reason.name(), "dbt-service 认证失败，请检查平台与服务的令牌配置");
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
            case TARGET_NOT_ALLOWED ->
                    new OntologyProblem(FORBIDDEN, reason.name(), "当前目标不在租户的授权范围");
            case TARGET_UNRESOLVED ->
                    new OntologyProblem(UNPROCESSABLE_ENTITY, reason.name(), "无法确认此表的物理定位");
            case COLUMN_MISSING ->
                    new OntologyProblem(UNPROCESSABLE_ENTITY, reason.name(), "仓库完整列清单缺少此列");
            case TYPE_CONFLICT ->
                    new OntologyProblem(UNPROCESSABLE_ENTITY, reason.name(), "声明的字段类型不兼容");
            default ->
                    new OntologyProblem(
                            SERVICE_UNAVAILABLE, UPSTREAM_UNAVAILABLE.name(), "当前部署的元数据暂不可用");
        };
    }
}
