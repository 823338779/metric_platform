package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.metricplatform.metadata.dataset.SourceMetadataInspector;
import com.metricplatform.metadata.relationship.RelationshipCardinalityValidator;
import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.service.DataSourcePersistenceService;
import com.metricplatform.query.QueryDtos;
import com.metricplatform.query.SemanticCatalogReader;
import com.metricplatform.query.compiler.SemanticSqlCompiler;
import com.metricplatform.query.planner.QueryPlanner;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实 PostgreSQL 验证发布事务；只有外部数据源探测被替换。 */
@SpringBootTest
@ActiveProfiles("test")
class GitImportServiceTest {
    @Autowired GitModelPreflight preflight;
    @Autowired GitPublicationService publisher;
    @Autowired DataSourcePersistenceService sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SemanticCatalogReader catalog;
    @Autowired QueryPlanner planner;
    @Autowired SemanticSqlCompiler compiler;
    @MockitoBean SourceMetadataInspector inspector;
    @MockitoBean RelationshipCardinalityValidator cardinality;
    GitImportService importer;
    String prefix;
    UUID sourceId;
    GitSyncProperties properties;

    @BeforeEach
    void setup() {
        prefix = "g" + UUID.randomUUID().toString().replace("-", "");
        sourceId = UUID.randomUUID();
        var source = new DataSourceEntity();
        source.setId(sourceId);
        source.setName(prefix);
        source.setJdbcUrl("jdbc:unavailable:test");
        source.setUsername("test");
        source.setStatus("ACTIVE");
        sources.save(source);
        properties = new GitSyncProperties();
        properties.setRepositoryId(prefix);
        properties.setDataSourceId(sourceId);
        properties.setSchema("public");
        properties.setModelName(prefix);
        importer = new GitImportService(properties, preflight, publisher);
        when(inspector.inspectObject(eq(sourceId), eq("public"), anyString()))
                .thenAnswer(
                        call ->
                                new SourceMetadataInspector.SourceObjectDefinition(
                                        "public",
                                        call.getArgument(2),
                                        "TABLE",
                                        List.of(
                                                new SourceMetadataInspector.SourceColumn(
                                                        "id", "BIGINT", 1),
                                                new SourceMetadataInspector.SourceColumn(
                                                        "amount", "DECIMAL(20,2)", 2),
                                                new SourceMetadataInspector.SourceColumn(
                                                        "day", "DATE", 3))));
    }

    @AfterEach
    void cleanup() {
        // 不使用测试外层事务，以便并发与回滚断言观察真实提交；测试后独立清理各自的数据源。
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            jdbc.queryForObject(
                                    "select set_config('metric_platform.git_repository',?,true)",
                                    String.class,
                                    prefix);
                            jdbc.update(
                                    "delete from dataset_relationship where source_dataset_id in (select id from dataset where data_source_id=?)",
                                    sourceId);
                            jdbc.update(
                                    "delete from metric where dataset_id in (select id from dataset where data_source_id=?)",
                                    sourceId);
                            jdbc.update(
                                    "delete from dimension where dataset_id in (select id from dataset where data_source_id=?)",
                                    sourceId);
                            jdbc.update("delete from dataset where data_source_id=?", sourceId);
                            jdbc.update(
                                    "delete from git_object_ownership where repository_id=?",
                                    prefix);
                            jdbc.update("delete from git_sync_state where repository_id=?", prefix);
                            jdbc.update("delete from data_source where id=?", sourceId);
                        });
    }

    @Test
    void publishesExistingCatalogAndKeepsIdentityAcrossReplayDeleteAndRestore() {
        assertThat(importer.apply(null, snapshot("a"), model(true))).isTrue();
        var first = ids();
        assertThat(first).hasSize(11);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from metric where name=? and not is_deleted",
                                Integer.class,
                                prefix + "_sales"))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select join_type from dataset_relationship where name=?",
                                String.class,
                                prefix + "_join"))
                .isEqualTo("INNER");
        assertThat(importer.apply("a", snapshot("a"), model(true))).isFalse();
        assertThat(ids()).isEqualTo(first);
        assertThat(importer.apply("a", snapshot("b"), model(false))).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from metric where name=? and not is_deleted",
                                Integer.class,
                                prefix + "_sales"))
                .isZero();
        assertThat(importer.apply("b", snapshot("c"), model(true))).isTrue();
        assertThat(ids()).isEqualTo(first);
        assertThat(importer.state().appliedCommit()).isEqualTo("c");
        verify(cardinality, times(3)).validate(any(), any(), any());
    }

    @Test
    void rollsBackEarlierWritesWhenLaterDatabaseWriteFails() {
        importer.apply(null, snapshot("a"), model(true));
        var original = model(true);
        var invalid =
                new OssieModel(
                        prefix,
                        original.datasets(),
                        List.of(
                                new OssieModel.Metric(
                                        prefix + "_sales",
                                        "orders",
                                        "amount",
                                        "SUM",
                                        "x".repeat(1001),
                                        "changed",
                                        null,
                                        List.of(),
                                        List.of())),
                        original.relationships());
        assertThatThrownBy(() -> importer.apply("a", snapshot("b"), invalid))
                .hasRootCauseMessage("ERROR: value too long for type character varying(1000)");
        assertThat(importer.state().appliedCommit()).isEqualTo("a");
        assertThat(
                        jdbc.queryForObject(
                                "select display_name from metric where name=?",
                                String.class,
                                prefix + "_sales"))
                .isEqualTo("Sales");
        assertThat(
                        jdbc.queryForObject(
                                "select version from dataset where data_source_id=? and name='orders'",
                                Long.class,
                                sourceId))
                .isZero();
    }

    @Test
    void rejectsStalePublicationAndFailureWithoutMovingCheckpoint() {
        importer.apply(null, snapshot("a"), model(true));
        importer.apply("a", snapshot("b"), model(true));
        assertThat(importer.apply("a", snapshot("c"), model(false))).isFalse();
        importer.failed("a", "OLD", "obsolete failure");
        assertThat(importer.state().errorCode()).isNull();
        importer.failed("b", "INVALID", "current failure");
        assertThat(importer.state().appliedCommit()).isEqualTo("b");
        assertThat(importer.state().errorCode()).isEqualTo("INVALID");
        assertThat(importer.apply("b", snapshot("c"), model(true))).isTrue();
        assertThat(importer.state().errorCode()).isNull();
    }

    @Test
    void refusesManualNameCollisionAndOwnedWritesAndReferences() {
        jdbc.update(
                "insert into dataset(id,data_source_id,name,schema_name,object_name) values (?,?,?,?,?)",
                UUID.randomUUID(),
                sourceId,
                "orders",
                "public",
                "orders");
        assertThatThrownBy(() -> importer.apply(null, snapshot("a"), model(true)))
                .isInstanceOf(RuntimeException.class);
        assertThat(importer.state().appliedCommit()).isNull();
        jdbc.update("delete from dataset where data_source_id=?", sourceId);
        importer.apply(null, snapshot("a"), model(true));
        UUID dataset =
                jdbc.queryForObject(
                        "select id from dataset where data_source_id=? and name='orders'",
                        UUID.class,
                        sourceId);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update dataset set description='manual' where id=?",
                                        dataset))
                .hasMessageContaining("Git");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into metric(id,dataset_id,name,display_name,aggregation) values (?,?,?,?,?)",
                                        UUID.randomUUID(),
                                        dataset,
                                        "manual",
                                        "Manual",
                                        "COUNT"))
                .hasMessageContaining("Git");
    }

    @Test
    void validatesPhysicalTypesBeforePublishing() {
        var original = model(true);
        var invalid =
                new OssieModel(
                        prefix,
                        original.datasets(),
                        List.of(
                                new OssieModel.Metric(
                                        prefix + "_sales",
                                        "orders",
                                        "day",
                                        "SUM",
                                        null,
                                        "bad",
                                        null,
                                        List.of(),
                                        List.of())),
                        original.relationships());
        assertThatThrownBy(() -> importer.apply(null, snapshot("a"), invalid))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ids()).isEmpty();
    }

    @Test
    void onlyOneConcurrentCandidateCanReplaceTheSameCheckpoint() throws Exception {
        importer.apply(null, snapshot("a"), model(true));
        var left = preflight.prepare(properties, model(true));
        var right = preflight.prepare(properties, model(true));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return publisher.publish(properties, "a", "b", left);
                            });
            var second =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return publisher.publish(properties, "a", "c", right);
                            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(importer.state().appliedCommit()).isIn("b", "c");
        assertThat(
                        jdbc.queryForObject(
                                "select version from dataset where data_source_id=? and name='orders'",
                                Long.class,
                                sourceId))
                .isEqualTo(1);
    }

    @Test
    void deletionOfEveryObjectAndRestorationKeepAllIdentities() {
        importer.apply(null, snapshot("a"), model(true));
        var before = ids();
        importer.apply("a", snapshot("b"), new OssieModel(prefix, List.of(), List.of(), List.of()));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dataset where data_source_id=? and not is_deleted",
                                Integer.class,
                                sourceId))
                .isZero();
        importer.apply("b", snapshot("c"), model(true));
        assertThat(ids()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dimension_time_granularity g join dimension d on d.id=g.dimension_id join dataset s on s.id=d.dataset_id where s.data_source_id=?",
                                Integer.class,
                                sourceId))
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dataset where data_source_id=? and not is_deleted",
                                Integer.class,
                                sourceId))
                .isEqualTo(2);
    }

    @Test
    void externalDependenciesRefuseTheEntireDeletionSnapshot() {
        importer.apply(null, snapshot("a"), model(true));
        var dataset =
                jdbc.queryForObject(
                        "select id from dataset where data_source_id=? and name='orders'",
                        UUID.class,
                        sourceId);
        // 模拟迁移或管理员曾引入的外部引用；正常管理入口已经由数据库守卫拒绝这种新引用。
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            jdbc.queryForObject(
                                    "select set_config('metric_platform.git_repository',?,true)",
                                    String.class,
                                    prefix);
                            jdbc.update(
                                    "insert into metric(id,dataset_id,name,display_name,aggregation) values (?,?,?,?,?)",
                                    UUID.randomUUID(),
                                    dataset,
                                    "external",
                                    "External",
                                    "COUNT");
                        });
        assertThatThrownBy(
                        () ->
                                importer.apply(
                                        "a",
                                        snapshot("b"),
                                        new OssieModel(prefix, List.of(), List.of(), List.of())))
                .hasMessageContaining("external dependencies");
        assertThat(importer.state().appliedCommit()).isEqualTo("a");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dataset where data_source_id=? and not is_deleted",
                                Integer.class,
                                sourceId))
                .isEqualTo(2);
    }

    @Test
    void clearsRemovedFixedFilterAndReadsCountOnDimensionThroughCatalog() {
        importer.apply(null, snapshot("a"), model(true));
        var original = model(true);
        var count =
                new OssieModel.Metric(
                        prefix + "_sales",
                        "orders",
                        "id",
                        "COUNT",
                        null,
                        "Count",
                        null,
                        List.of(),
                        List.of());
        importer.apply(
                "a",
                snapshot("b"),
                new OssieModel(
                        prefix, original.datasets(), List.of(count), original.relationships()));
        var metric =
                jdbc.queryForObject(
                        "select id from metric where name=?", UUID.class, prefix + "_sales");
        assertThat(catalog.metric(metric).metric().getFixedFilterExpression()).isNull();
        assertThat(catalog.metric(metric).field().getPhysicalName()).isEqualTo("id");
        assertThat(
                        compiler.compile(
                                        planner.plan(
                                                new QueryDtos.QueryRequest(
                                                        List.of(metric),
                                                        List.of(),
                                                        java.util.Set.of())))
                                .sql())
                .contains("COUNT(`d0`.`id`)");
        importer.failed("b", "TEMPORARY", "temporary error");
        importer.healthy("a");
        assertThat(importer.state().errorCode()).isEqualTo("TEMPORARY");
        importer.healthy("b");
        assertThat(importer.state().errorCode()).isNull();
    }

    @Test
    void rejectsInvalidTemporalLiteralsAndUnsupportedScalarAggregates() {
        var original = model(true);
        var invalid =
                new OssieModel.Metric(
                        prefix + "_sales",
                        "orders",
                        "amount",
                        "SUM",
                        null,
                        "bad",
                        null,
                        List.of(),
                        List.of(new OssieModel.Predicate("day", "GTE", List.of("2026-02-30"))));
        assertThatThrownBy(
                        () ->
                                importer.apply(
                                        null,
                                        snapshot("a"),
                                        new OssieModel(
                                                prefix,
                                                original.datasets(),
                                                List.of(invalid),
                                                original.relationships())))
                .hasMessageContaining("valid ISO");
        when(inspector.inspectObject(eq(sourceId), eq("public"), anyString()))
                .thenAnswer(
                        call ->
                                new SourceMetadataInspector.SourceObjectDefinition(
                                        "public",
                                        call.getArgument(2),
                                        "TABLE",
                                        List.of(
                                                new SourceMetadataInspector.SourceColumn(
                                                        "id", "BIGINT", 1),
                                                new SourceMetadataInspector.SourceColumn(
                                                        "amount", "JSONB", 2),
                                                new SourceMetadataInspector.SourceColumn(
                                                        "day", "DATE", 3))));
        for (String aggregate : List.of("MIN", "MAX", "COUNT", "COUNT_DISTINCT")) {
            var metric =
                    new OssieModel.Metric(
                            prefix + "_sales",
                            "orders",
                            "amount",
                            aggregate,
                            null,
                            "bad",
                            null,
                            List.of(),
                            List.of());
            assertThatThrownBy(
                            () ->
                                    importer.apply(
                                            null,
                                            snapshot("a"),
                                            new OssieModel(
                                                    prefix,
                                                    original.datasets(),
                                                    List.of(metric),
                                                    original.relationships())))
                    .hasMessageContaining("supported scalar");
        }
        assertThat(ids()).isEmpty();
    }

    private List<UUID> ids() {
        return jdbc.queryForList(
                "select object_id from git_object_ownership where repository_id=? order by kind,logical_name",
                UUID.class,
                prefix);
    }

    @Test
    void repositoryBindingCannotDriftOnHealthyOrNewPublication() {
        importer.apply(null, snapshot("a"), model(true));
        var prepared = preflight.prepare(properties, model(true));
        properties.setSchema("different");
        assertThatThrownBy(() -> importer.healthy("a")).hasMessageContaining("immutable");
        assertThatThrownBy(() -> publisher.publish(properties, "a", "b", prepared))
                .hasMessageContaining("immutable");
        properties.setSchema("public");
        properties.setDataSourceId(UUID.randomUUID());
        assertThatThrownBy(() -> importer.healthy("a")).hasMessageContaining("immutable");
        assertThatThrownBy(() -> publisher.publish(properties, "a", "b", prepared))
                .hasMessageContaining("immutable");
        assertThat(importer.state().appliedCommit()).isEqualTo("a");
    }

    @Test
    void rejectsFloatingPointJoinAgainstExactNumericUniqueKey() {
        when(inspector.inspectObject(eq(sourceId), eq("public"), anyString()))
                .thenAnswer(
                        call ->
                                new SourceMetadataInspector.SourceObjectDefinition(
                                        "public",
                                        call.getArgument(2),
                                        "TABLE",
                                        List.of(
                                                new SourceMetadataInspector.SourceColumn(
                                                        "id",
                                                        call.getArgument(2).equals("orders")
                                                                ? "DOUBLE"
                                                                : "BIGINT",
                                                        1),
                                                new SourceMetadataInspector.SourceColumn(
                                                        "amount", "DECIMAL(20,2)", 2),
                                                new SourceMetadataInspector.SourceColumn(
                                                        "day", "DATE", 3))));
        assertThatThrownBy(() -> importer.apply(null, snapshot("a"), model(true)))
                .hasMessageContaining("incompatible");
        verifyNoInteractions(cardinality);
        assertThat(ids()).isEmpty();
    }

    @Test
    void restoresTombstoneToNewColumnWhenAnotherFieldUsesItsOldColumn() {
        var a =
                new OssieModel.Field(
                        "a", "id", null, "A", OssieModel.Role.MEASURE, null, List.of());
        var b =
                new OssieModel.Field(
                        "b", "id", null, "B", OssieModel.Role.MEASURE, null, List.of());
        importer.apply(null, snapshot("a"), simpleFields(List.of(a)));
        var aId =
                jdbc.queryForObject(
                        "select object_id from git_object_ownership where repository_id=? and kind='dataset_field' and logical_name='orders.a'",
                        UUID.class,
                        prefix);
        importer.apply("a", snapshot("b"), simpleFields(List.of(b)));
        var movedA =
                new OssieModel.Field(
                        "a", "amount", null, "A", OssieModel.Role.MEASURE, null, List.of());
        importer.apply("b", snapshot("c"), simpleFields(List.of(movedA, b)));
        assertThat(
                        jdbc.queryForObject(
                                "select physical_name from dataset_field where id=? and not is_deleted",
                                String.class,
                                aId))
                .isEqualTo("amount");
    }

    private OssieModel simpleFields(List<OssieModel.Field> fields) {
        return new OssieModel(
                prefix,
                List.of(
                        new OssieModel.Dataset(
                                "orders", "orders", null, "Orders", null, List.of(), List.of(),
                                fields)),
                List.of(),
                List.of());
    }

    @Test
    void swapsPhysicalColumnsWithoutChangingLogicalFieldIds() {
        importer.apply(null, snapshot("a"), model(true));
        var before = ids();
        var original = model(true);
        var order = original.datasets().getFirst();
        var swapped =
                order.fields().stream()
                        .map(
                                field ->
                                        new OssieModel.Field(
                                                field.name(),
                                                field.name().equals("id")
                                                        ? "amount"
                                                        : field.name().equals("amount")
                                                                ? "id"
                                                                : field.column(),
                                                field.description(),
                                                field.displayName(),
                                                field.role(),
                                                field.unit(),
                                                field.synonyms()))
                        .toList();
        var replacement =
                new OssieModel.Dataset(
                        order.name(),
                        order.source(),
                        order.description(),
                        order.displayName(),
                        order.defaultTimeField(),
                        order.primaryKey(),
                        order.synonyms(),
                        swapped);
        importer.apply(
                "a",
                snapshot("b"),
                new OssieModel(
                        prefix,
                        List.of(replacement, original.datasets().getLast()),
                        original.metrics(),
                        original.relationships()));
        assertThat(ids()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select f.physical_name from dataset_field f join git_object_ownership o on o.object_id=f.id where o.repository_id=? and o.kind='dataset_field' and o.logical_name='orders.id'",
                                String.class,
                                prefix))
                .isEqualTo("amount");
    }

    private GitSnapshot snapshot(String commit) {
        return new GitSnapshot(commit, new byte[0]);
    }

    private OssieModel model(boolean withMetric) {
        var fields =
                List.of(
                        new OssieModel.Field(
                                "id", "id", null, "ID", OssieModel.Role.DIMENSION, null, List.of()),
                        new OssieModel.Field(
                                "amount",
                                "amount",
                                null,
                                "Amount",
                                OssieModel.Role.MEASURE,
                                null,
                                List.of()),
                        new OssieModel.Field(
                                "day", "day", null, "Day", OssieModel.Role.TIME, null, List.of()));
        var orders =
                new OssieModel.Dataset(
                        "orders",
                        "orders",
                        null,
                        "Orders",
                        "day",
                        List.of("id"),
                        List.of(),
                        fields);
        var regions =
                new OssieModel.Dataset(
                        "regions",
                        "regions",
                        null,
                        "Regions",
                        null,
                        List.of("id"),
                        List.of(),
                        fields.subList(0, 1));
        var metric =
                new OssieModel.Metric(
                        prefix + "_sales",
                        "orders",
                        "amount",
                        "SUM",
                        null,
                        "Sales",
                        "CNY",
                        List.of("收入"),
                        List.of(new OssieModel.Predicate("day", "GTE", List.of("2026-01-01"))));
        return new OssieModel(
                prefix,
                List.of(orders, regions),
                withMetric ? List.of(metric) : List.of(),
                List.of(
                        new OssieModel.Relationship(
                                prefix + "_join", "orders", "regions", "id", "id", "INNER")));
    }
}
