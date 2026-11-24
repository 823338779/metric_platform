package com.metricplatform.persistence.ontology;

import static com.metricplatform.ontology.OntologyException.Code.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.ontology.OntologyException;
import com.metricplatform.ontology.OntologyReferences;
import com.metricplatform.ontology.OntologyStore;
import java.util.*;
import java.util.function.BiConsumer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;

public final class Neo4jOntologyStore implements OntologyStore {
    private static final String SCOPE = "{tenantId: $tenant, environmentId: $env}";
    private static final String KEY = "{tenantId: $tenant, environmentId: $env, id: $id}";
    private static final String SCOPE_NODE = "(s:OntologyScope " + SCOPE + ")";
    private static final String OBJECT = "(o:OntologyObject " + KEY + ")";
    private static final String DELETE_RELATION =
            "MATCH ()-[r:ONTOLOGY_RELATION " + KEY + "]->() DELETE r";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Driver driver;
    private volatile SchemaAttempt initialization = new SchemaAttempt();

    private static final class SchemaAttempt {
        private volatile boolean ready;
        private RuntimeException failure;
    }

    public Neo4jOntologyStore(Driver driver) {
        this.driver = driver;
    }

    private void initialize(Session session) {
        var attempt = initialization;
        if (attempt.ready) return;
        synchronized (attempt) {
            if (attempt.ready) return;
            if (attempt.failure != null) throw attempt.failure;
            try {
                createConstraints(session);
                attempt.ready = true;
            } catch (RuntimeException ex) {
                // 等待同一次初始化的请求共享失败结果，后续独立请求可以重试。
                attempt.failure = ex;
                initialization = new SchemaAttempt();
                throw ex;
            }
        }
    }

    private static void createConstraints(Session session) {
        for (String constraint : List.of(
                "ontology_scope IF NOT EXISTS FOR (n:OntologyScope) REQUIRE (n.tenantId, n.environmentId)",
                "ontology_object IF NOT EXISTS FOR (n:OntologyObject) REQUIRE (n.tenantId, n.environmentId, n.id)",
                "ontology_property IF NOT EXISTS FOR (n:OntologyProperty) REQUIRE (n.tenantId, n.environmentId, n.id)",
                "ontology_relation IF NOT EXISTS FOR ()-[n:ONTOLOGY_RELATION]-() REQUIRE (n.tenantId, n.environmentId, n.id)")) {
            session.run("CREATE CONSTRAINT " + constraint + " IS UNIQUE").consume();
        }
    }

    private <T> T transaction(TransactionCallback<T> work) {
        try (var session = driver.session()) {
            initialize(session);
            return session.executeWrite(work);
        } catch (Neo4jException ex) {
            var unavailable = new OntologyException(UNAVAILABLE, "本体图库暂时不可用");
            unavailable.initCause(ex);
            throw unavailable;
        }
    }

    private static Map<String, Object> params(Scope scope, Object... entries) {
        var params = new HashMap<String, Object>();
        params.put("tenant", scope.tenantId());
        params.put("env", scope.environmentId().toString());
        for (int i = 0; i < entries.length; i += 2) {
            Object value = entries[i + 1];
            params.put((String) entries[i], value instanceof UUID ? value.toString() : value);
        }
        return params;
    }

    // Neo4j 默认隔离级别为 read-committed。读完整拓扑也持有范围锁，避免混合两个编辑序号的数据。
    private static long lock(TransactionContext tx, Scope scope, boolean create) {
        String locate = create ? "MERGE " + SCOPE_NODE + " ON CREATE SET s.revision = 0" : "MATCH " + SCOPE_NODE;
        var result = tx.run(locate + " SET s.lock = true REMOVE s.lock RETURN s.revision", params(scope));
        return result.hasNext() ? result.single().get(0).asLong() : -1;
    }

    @Override
    public Topology read(Scope scope) {
        return transaction(tx -> {
            long revision = lock(tx, scope, false);
            return revision < 0 ? new Topology(0, List.of(), List.of()) : topology(tx, scope, revision);
        });
    }

    private static Topology topology(TransactionContext tx, Scope scope, long revision) {
        var objects = tx.run("""
                MATCH (o:OntologyObject {tenantId: $tenant, environmentId: $env})
                OPTIONAL MATCH (o)-[:HAS_PROPERTY]->(p:OntologyProperty {tenantId: $tenant, environmentId: $env})
                WITH o, p ORDER BY p.position
                RETURN o, collect(p) AS properties ORDER BY o.id
                """, params(scope)).list(row -> {
            Value o = row.get("o");
            var properties = row.get("properties").asList(p -> new Property(id(p, "id"), p.get("name").asString(),
                    p.get("columnName").asString(), p.get("declaredType").asString(null), evidence(p)));
            return new ObjectType(id(o, "id"), o.get("name").asString(), o.get("description").asString(null),
                    id(o, "datasetAssetId"), id(o, "primaryPropertyId"), properties);
        });
        var relations = tx.run("""
                MATCH (a:OntologyObject {tenantId: $tenant, environmentId: $env})
                      -[r:ONTOLOGY_RELATION {tenantId: $tenant, environmentId: $env}]->
                      (b:OntologyObject {tenantId: $tenant, environmentId: $env})
                RETURN r, a.id AS src, b.id AS dst ORDER BY r.id
                """, params(scope)).list(row -> {
            Value r = row.get("r");
            return new Relation(id(r, "id"), r.get("name").asString(), r.get("description").asString(null),
                    UUID.fromString(row.get("src").asString()), id(r, "sourcePropertyId"),
                    UUID.fromString(row.get("dst").asString()), id(r, "targetPropertyId"),
                    Cardinality.valueOf(r.get("cardinality").asString()));
        });
        return new Topology(revision, objects, relations);
    }

    private static PropertyEvidence evidence(Value node) {
        var json = node.get("physicalEvidence").asString(null);
        if (json == null) return null;
        try { return JSON.readValue(json, PropertyEvidence.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法读取字段证据",ex); }
    }

    @Override public long beginObservation(Scope scope) {
        return transaction(tx -> {
            lock(tx,scope,true);
            return tx.run("MATCH " + SCOPE_NODE + " SET s.issued=coalesce(s.issued,0)+1 RETURN s.issued",
                    params(scope)).single().get(0).asLong();
        });
    }

    // 范围锁覆盖比较和写入。拒绝迟到的结果，避免报告与属性基准取自不同次核实。
    private static boolean currentSequence(TransactionContext tx,Scope scope,ObservationWrite observation) {
        return tx.run("MATCH " + SCOPE_NODE + " RETURN coalesce(s.applied,0)",params(scope))
                .single().get(0).asLong() < observation.sequence();
    }

    private static void applyEvidence(TransactionContext tx, Scope scope,ObservationWrite observation) {
        for (var delta : observation.updates()) {
            var args = params(scope,"id",delta.propertyId());
            var row = tx.run("MATCH (p:OntologyProperty " + KEY + ") RETURN p",args);
            if (!row.hasNext()) throw new OntologyException(CONFLICT,"绑定已变更");
            var previous = evidence(row.single().get(0));
            if (!Objects.equals(previous,delta.expectedPrevious()))
                throw new OntologyException(CONFLICT,"类型基准已更新");
            try { args.put("json",JSON.writeValueAsString(delta.value())); }
            catch (JsonProcessingException ex) { throw new IllegalStateException("无法编码字段证据",ex); }
            tx.run("MATCH (p:OntologyProperty " + KEY + ") SET p.physicalEvidence=$json",args).consume();
        }
        tx.run("MATCH " + SCOPE_NODE + " SET s.applied=$seq",params(scope,"seq",observation.sequence())).consume();
        observation.checkDeployment().run();
    }

    private static UUID id(Value item, String field) {
        return UUID.fromString(item.get(field).asString());
    }

    private long mutate(Scope scope, long expected, BiConsumer<TransactionContext, Topology> mutation) {
        return mutate(scope,expected,null,mutation);
    }

    private long mutate(Scope scope,long expected,ObservationWrite observation,BiConsumer<TransactionContext,Topology> mutation) {
        return transaction(tx -> {
            long revision = lock(tx, scope, true);
            if (observation != null && !currentSequence(tx,scope,observation))
                throw new OntologyException(CONFLICT,"核实结果已过期");
            if (revision != expected) throw new OntologyException(CONFLICT, "图已更新，请刷新后重试");
            mutation.accept(tx, topology(tx, scope, revision));
            tx.run("MATCH " + SCOPE_NODE + " SET s.revision = s.revision + 1", params(scope)).consume();
            tx.run("MATCH (c:OntologyCheck " + SCOPE + ") DELETE c", params(scope)).consume();
            if (observation != null) applyEvidence(tx,scope,observation);
            return revision + 1;
        });
    }

    @Override
    public long createObject(Scope scope, long expected, ObjectType object, UUID buildId, Set<UUID> verified) {
        return writeObservedObject(scope, expected, object, true, buildId, verified,null);
    }

    @Override
    public long updateObject(Scope scope, long expected, ObjectType object, UUID buildId, Set<UUID> verified) {
        return writeObservedObject(scope, expected, object, false, buildId, verified,null);
    }

    @Override public long writeObservedObject(Scope scope, long expected, ObjectType object, boolean create, UUID buildId, Set<UUID> verified,ObservationWrite observation) {
        return mutate(scope, expected, observation, (tx, graph) -> {
            require(object != null && object.id() != null && hasText(object.name()) && object.datasetAssetId() != null
                    && object.properties() != null, "对象名称、数据集和属性不能为空");
            var old = graph.objects().stream().filter(o -> o.id().equals(object.id())).findFirst();
            checkTarget(create, old.isPresent());

            var properties = new HashMap<UUID, Property>();
            for (Property p : object.properties()) {
                require(p != null && p.id() != null && hasText(p.name()) && hasText(p.columnName()), "属性名称和绑定列不能为空");
                require(properties.put(p.id(), p) == null, "属性 ID 重复");
            }
            for (ObjectType other : graph.objects()) {
                if (!other.id().equals(object.id())) {
                    require(other.properties().stream().noneMatch(p -> properties.containsKey(p.id())), "属性已有其他所属对象");
                }
            }
            var changed = OntologyReferences.checkUpdate(graph, old.orElse(null), object);
            require(properties.containsKey(object.primaryPropertyId()), "主键必须是本对象的属性");
            require(verified != null && properties.keySet().containsAll(verified)
                    && (verified.isEmpty() || buildId != null), "属性的核验依据无效");

            tx.run("MATCH " + SCOPE_NODE + " MERGE " + OBJECT + " MERGE (s)-[:HAS_OBJECT]->(o) "
                    + "SET o.name=$name, o.description=$description, o.datasetAssetId=$asset, o.primaryPropertyId=$primary",
                    params(scope, "id", object.id(), "name", object.name(), "description", object.description(),
                            "asset", object.datasetAssetId(), "primary", object.primaryPropertyId())).consume();
            var rows = new ArrayList<Map<String, Object>>();
            for (Property p : object.properties()) {
                var row = new HashMap<String, Object>();
                row.put("id", p.id().toString());
                row.put("name", p.name());
                row.put("columnName", p.columnName());
                row.put("declaredType", p.declaredType());
                row.put("position", rows.size());
                if (verified.contains(p.id())) {
                    row.put("checkedBuildId", buildId.toString());
                    row.put("checkedRevision", graph.revision() + 1);
                } else if (changed.contains(p.id())) {
                    row.put("checkedBuildId", null);
                    row.put("checkedRevision", null);
                }
                if (old.isPresent() && (!object.datasetAssetId().equals(old.get().datasetAssetId())
                        || old.get().properties().stream().anyMatch(previous -> previous.id().equals(p.id())
                            && !previous.columnName().equals(p.columnName())))) row.put("physicalEvidence",null);
                rows.add(row);
            }
            var args = params(scope, "id", object.id(), "rows", rows);
            tx.run("MATCH " + OBJECT + "-[:HAS_PROPERTY]->(p:OntologyProperty " + SCOPE + ") "
                    + "WHERE NOT p.id IN [row IN $rows | row.id] DETACH DELETE p", args).consume();
            tx.run("MATCH " + OBJECT + """
                     UNWIND $rows AS row
                     MERGE (p:OntologyProperty {tenantId: $tenant, environmentId: $env, id: row.id})
                     SET p += row
                     MERGE (o)-[:HAS_PROPERTY]->(p)
                    """, args).consume();
        });
    }

    @Override
    public long deleteObject(Scope scope, long expected, UUID id) {
        return mutate(scope, expected, (tx, graph) -> {
            checkTarget(false, graph.objects().stream().anyMatch(o -> o.id().equals(id)));
            OntologyReferences.checkDelete(graph, id);
            tx.run("MATCH " + OBJECT + " OPTIONAL MATCH (o)-[:HAS_PROPERTY]->(p:OntologyProperty " + SCOPE
                    + ") DETACH DELETE p, o", params(scope, "id", id)).consume();
        });
    }

    private static void checkTarget(boolean create, boolean exists) {
        if (create && exists) throw new OntologyException(CONFLICT, "资源 ID 已存在");
        if (!create && !exists) throw new OntologyException(NOT_FOUND, "当前环境没有此资源");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new OntologyException(INVALID, message);
    }

    @Override
    public long createRelation(Scope scope, long expected, Relation relation, UUID buildId) {
        return writeObservedRelation(scope, expected, relation, true, buildId,null);
    }

    @Override
    public long updateRelation(Scope scope, long expected, Relation relation, UUID buildId) {
        return writeObservedRelation(scope, expected, relation, false, buildId,null);
    }

    @Override public long writeObservedRelation(Scope scope, long expected, Relation r, boolean create, UUID buildId,ObservationWrite observation) {
        return mutate(scope, expected, observation, (tx, graph) -> {
            require(r != null && r.id() != null && hasText(r.name()) && r.cardinality() != null
                    && r.sourceObjectId() != null && r.sourcePropertyId() != null
                    && r.targetObjectId() != null && r.targetPropertyId() != null, "关系需要名称、基数和两端属性");
            var old = graph.relations().stream().filter(v -> v.id().equals(r.id())).findFirst();
            checkTarget(create, old.isPresent());
            checkEndpoint(graph, r.sourceObjectId(), r.sourcePropertyId());
            checkEndpoint(graph, r.targetObjectId(), r.targetPropertyId());
            var args = params(scope, "id", r.id(), "source", r.sourceObjectId(), "target", r.targetObjectId(),
                    "sourceProperty", r.sourcePropertyId(), "targetProperty", r.targetPropertyId(),
                    "name", r.name(), "description", r.description(), "cardinality", r.cardinality().name(),
                    "build", buildId, "checkedRevision", buildId == null ? null : graph.revision() + 1);
            if (buildId == null && old.isPresent()
                    && old.get().sourceObjectId().equals(r.sourceObjectId()) && old.get().targetObjectId().equals(r.targetObjectId())
                    && old.get().sourcePropertyId().equals(r.sourcePropertyId()) && old.get().targetPropertyId().equals(r.targetPropertyId())) {
                tx.run("MATCH ()-[r:ONTOLOGY_RELATION " + KEY + "]->() "
                        + "SET r.name=$name, r.description=$description, r.cardinality=$cardinality", args).consume();
                return;
            }
            if (!create) tx.run(DELETE_RELATION, args).consume();
            tx.run("""
                    MATCH (a:OntologyObject {tenantId: $tenant, environmentId: $env, id: $source}),
                          (b:OntologyObject {tenantId: $tenant, environmentId: $env, id: $target})
                    CREATE (a)-[r:ONTOLOGY_RELATION {tenantId: $tenant, environmentId: $env, id: $id}]->(b)
                    SET r.sourcePropertyId=$sourceProperty, r.targetPropertyId=$targetProperty,
                        r.name=$name, r.description=$description, r.cardinality=$cardinality,
                        r.checkedBuildId=$build, r.checkedRevision=$checkedRevision
                    """, args).consume();
        });
    }

    private static void checkEndpoint(Topology graph, UUID objectId, UUID propertyId) {
        var object = graph.objects().stream().filter(o -> o.id().equals(objectId)).findFirst()
                .orElseThrow(() -> new OntologyException(NOT_FOUND, "当前环境没有此端点对象"));
        require(object.properties().stream().anyMatch(p -> p.id().equals(propertyId)), "端点属性不属于指定对象");
    }

    @Override
    public long deleteRelation(Scope scope, long expected, UUID id) {
        return mutate(scope, expected, (tx, graph) -> {
            checkTarget(false, graph.relations().stream().anyMatch(r -> r.id().equals(id)));
            tx.run(DELETE_RELATION, params(scope, "id", id)).consume();
        });
    }

    @Override
    public boolean saveReport(Scope scope, BindingReport report) {
        return saveObservedReport(scope,report,null);
    }

    @Override public boolean saveObservedReport(Scope scope, BindingReport report,ObservationWrite observation) {
        return transaction(tx -> {
            if (lock(tx, scope, true) != report.revision()) return false;
            if (observation != null && !currentSequence(tx,scope,observation)) return false;
            try {
                tx.run("""
                        MERGE (c:OntologyCheck {tenantId:$tenant, environmentId:$env, build:$build})
                        SET c.revision=$revision, c.report=$report
                        """, params(scope, "build", Objects.toString(report.buildId(), ""),
                        "revision", report.revision(), "report", JSON.writeValueAsString(report))).consume();
                if (observation != null) applyEvidence(tx,scope,observation);
                return true;
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("无法编码绑定报告", ex);
            }
        });
    }

    @Override
    public Optional<BindingReport> readReport(Scope scope, UUID buildId, long revision) {
        return transaction(tx -> {
            if (lock(tx, scope, false) != revision) return Optional.empty();
            var result = tx.run("""
                    MATCH (c:OntologyCheck {tenantId:$tenant, environmentId:$env, build:$build, revision:$revision})
                    RETURN c.report
                    """, params(scope, "build", Objects.toString(buildId, ""), "revision", revision));
            if (!result.hasNext()) return Optional.empty();
            try {
                var report = JSON.readValue(result.single().get(0).asString(), BindingReport.class);
                return report.formatVersion() == 2 ? Optional.of(report) : Optional.empty();
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("无法读取绑定报告", ex);
            }
        });
    }
}
