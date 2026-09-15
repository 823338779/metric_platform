package com.metricplatform.gitsync;

import static com.metricplatform.gitsync.GitObjectKind.*;
import static com.metricplatform.metadata.relationship.RelationshipDtos.Cardinality.MANY_TO_ONE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.metadata.dataset.FilterValidator;
import com.metricplatform.metadata.dataset.SourceMetadataInspector;
import com.metricplatform.metadata.relationship.RelationshipCardinalityValidator;
import com.metricplatform.persistence.entity.*;
import com.metricplatform.persistence.service.DataSourcePersistenceService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 在元数据事务外完成物理对象、聚合类型与关系唯一性校验，再纯组装候选实体。 */
@Component
@RequiredArgsConstructor
public class GitModelPreflight {
    private static final String ACTIVE = "ACTIVE", ENABLED = "ENABLED", EMPTY = "", SEPARATOR = ".";
    private static final String INTERNAL = "INTERNAL",
            UNCERTIFIED = "UNCERTIFIED",
            UNKNOWN = "UNKNOWN";
    private static final String TIME = "TIME",
            STANDARD = "STANDARD",
            DAY = "DAY",
            VALID = "VALID",
            BASE = "BASE";
    private static final Set<String> NUMERIC =
            Set.of(
                    "TINYINT",
                    "SMALLINT",
                    "INT",
                    "INT2",
                    "INT4",
                    "INT8",
                    "INTEGER",
                    "BIGINT",
                    "LARGEINT",
                    "FLOAT",
                    "FLOAT4",
                    "FLOAT8",
                    "REAL",
                    "DOUBLE",
                    "DOUBLE PRECISION",
                    "NUMERIC",
                    "DECIMAL",
                    "DECIMALV2",
                    "DECIMAL32",
                    "DECIMAL64",
                    "DECIMAL128");
    private static final Set<String> TEMPORAL =
            Set.of(
                    "DATE",
                    "DATETIME",
                    "TIMESTAMP",
                    "TIMESTAMPTZ",
                    "TIMESTAMP WITHOUT TIME ZONE",
                    "TIMESTAMP WITH TIME ZONE");
    private static final Set<String> TEXT =
            Set.of("CHAR", "VARCHAR", "STRING", "TEXT", "CHARACTER", "CHARACTER VARYING", "BPCHAR");
    private static final Set<String> BOOLEAN = Set.of("BOOL", "BOOLEAN");
    private static final Set<String> NUMERIC_AGGREGATIONS = Set.of("SUM", "AVG");
    private static final Set<String> COMPARABLE_AGGREGATIONS = Set.of("MIN", "MAX");
    private static final Set<String> ZONED_TEMPORAL =
            Set.of("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE");
    private static final String ID_SEPARATOR = "\u0000",
            TYPE_PARAMETERS = "\\([^)]*\\)",
            TYPE_SPACES = "\\s+",
            SPACE = " ";
    private static final String DATE = "DATE";
    private final SourceMetadataInspector inspector;
    private final RelationshipCardinalityValidator cardinality;
    private final DataSourcePersistenceService sources;
    private final FilterValidator filterValidator;
    private final ObjectMapper json;

    GitPreparedSnapshot prepare(GitSyncProperties properties, OssieModel model) {
        if (!Objects.equals(properties.getModelName(), model.name()))
            throw new IllegalArgumentException("Git model name differs from configured model");
        var source = sources.getById(properties.getDataSourceId());
        if (source == null || !Set.of(ACTIVE, ENABLED).contains(source.getStatus()))
            throw new IllegalArgumentException("Git data source is absent or inactive");
        var ownerships = new ArrayList<GitObjectOwnershipEntity>();
        var datasets = new LinkedHashMap<String, DatasetEntity>();
        var fields = new LinkedHashMap<String, DatasetFieldEntity>();
        var dimensions = new ArrayList<DimensionEntity>();
        var metrics = new ArrayList<MetricEntity>();
        var relationships = new ArrayList<DatasetRelationshipEntity>();
        var pairs = new ArrayList<DatasetRelationshipFieldPairEntity>();

        // 只接受配置的数据源/schema；外部元数据给出真实类型，YAML 不能自行声明类型绕过检查。
        for (var definition : model.datasets()) {
            var physical =
                    inspector.inspectObject(
                            properties.getDataSourceId(),
                            properties.getSchema(),
                            definition.source());
            var dataset = new DatasetEntity();
            dataset.setId(identity(properties, DATASET, definition.name(), ownerships));
            dataset.setDataSourceId(properties.getDataSourceId());
            dataset.setName(definition.name());
            dataset.setSchemaName(physical.schema());
            dataset.setObjectName(physical.name());
            dataset.setObjectType(physical.type());
            dataset.setDescription(empty(definition.description()));
            dataset.setAliases(write(definition.synonyms()));
            dataset.setStatus(ACTIVE);
            dataset.setCertificationStatus(UNCERTIFIED);
            dataset.setQualityStatus(UNKNOWN);
            if (definition.defaultTimeField() != null)
                dataset.setDefaultTimeDimensionId(
                        id(
                                properties,
                                DIMENSION,
                                key(definition.name(), definition.defaultTimeField())));
            datasets.put(definition.name(), dataset);
            for (var fieldDefinition : definition.fields()) {
                var column =
                        physical.columns().stream()
                                .filter(value -> value.name().equals(fieldDefinition.column()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Git physical field not found: "
                                                                + fieldDefinition.column()));
                if (fieldDefinition.role() == OssieModel.Role.TIME
                        && !TEMPORAL.contains(type(column.type())))
                    throw new IllegalArgumentException("TIME requires a temporal physical field");
                String logical = key(definition.name(), fieldDefinition.name());
                var field = new DatasetFieldEntity();
                field.setId(identity(properties, FIELD, logical, ownerships));
                field.setDatasetId(dataset.getId());
                field.setPhysicalName(column.name());
                field.setDisplayName(
                        display(fieldDefinition.displayName(), fieldDefinition.name()));
                field.setDescription(empty(fieldDefinition.description()));
                field.setStarrocksType(column.type());
                field.setSelected(true);
                field.setDimensionEnabled(fieldDefinition.role() != OssieModel.Role.MEASURE);
                field.setMetricEnabled(fieldDefinition.role() == OssieModel.Role.MEASURE);
                field.setSensitivityLevel(INTERNAL);
                fields.put(logical, field);
                if (fieldDefinition.role() != OssieModel.Role.MEASURE) {
                    var dimension = new DimensionEntity();
                    dimension.setId(identity(properties, DIMENSION, logical, ownerships));
                    dimension.setDatasetId(dataset.getId());
                    dimension.setDatasetFieldId(field.getId());
                    dimension.setName(fieldDefinition.name());
                    dimension.setDisplayName(field.getDisplayName());
                    dimension.setDescription(field.getDescription());
                    dimension.setDimensionType(
                            fieldDefinition.role() == OssieModel.Role.TIME ? TIME : STANDARD);
                    dimension.setDefaultGranularity(
                            fieldDefinition.role() == OssieModel.Role.TIME ? DAY : null);
                    dimension.setStatus(ACTIVE);
                    dimension.setCertificationStatus(UNCERTIFIED);
                    dimension.setSensitivityLevel(INTERNAL);
                    dimension.setAliases(write(fieldDefinition.synonyms()));
                    dimensions.add(dimension);
                }
            }
        }

        // 固定谓词仅引用本数据集内部字段 UUID；同时校验 JSON 值的类型和日期有效性。
        for (var definition : model.metrics()) {
            var dataset = required(datasets, definition.dataset());
            var field = required(fields, key(definition.dataset(), definition.field()));
            String fieldType = type(field.getStarrocksType());
            boolean comparable =
                    NUMERIC.contains(fieldType)
                            || TEXT.contains(fieldType)
                            || TEMPORAL.contains(fieldType);
            if (!comparable && !BOOLEAN.contains(fieldType))
                throw new IllegalArgumentException(
                        "Git aggregates require a supported scalar field");
            if (NUMERIC_AGGREGATIONS.contains(definition.aggregation())
                    && !NUMERIC.contains(fieldType))
                throw new IllegalArgumentException("SUM/AVG requires a numeric physical field");
            if (COMPARABLE_AGGREGATIONS.contains(definition.aggregation()) && !comparable)
                throw new IllegalArgumentException("MIN/MAX requires a comparable physical field");
            var metric = new MetricEntity();
            metric.setId(identity(properties, METRIC, definition.name(), ownerships));
            metric.setDatasetId(dataset.getId());
            metric.setDatasetFieldId(field.getId());
            metric.setName(definition.name());
            metric.setDisplayName(display(definition.displayName(), definition.name()));
            metric.setDescription(empty(definition.description()));
            metric.setAggregation(definition.aggregation());
            metric.setUnit(empty(definition.unit()));
            metric.setAliases(write(definition.synonyms()));
            metric.setStatus(ACTIVE);
            metric.setCertificationStatus(UNCERTIFIED);
            metric.setMetricType(BASE);
            metric.setDefaultTimeDimensionId(dataset.getDefaultTimeDimensionId());
            var predicates = new ArrayList<FilterExpression>();
            for (var predicate : definition.predicates()) {
                var predicateField = required(fields, key(definition.dataset(), predicate.field()));
                predicate
                        .values()
                        .forEach(
                                value -> validateLiteral(predicateField.getStarrocksType(), value));
                predicates.add(
                        new FilterExpression(
                                null,
                                null,
                                predicateField.getId(),
                                FilterExpression.FilterOperator.valueOf(predicate.operator()),
                                predicate.values().stream()
                                        .map(value -> json.<JsonNode>valueToTree(value))
                                        .toList()));
            }
            if (!predicates.isEmpty()) {
                var filter =
                        new FilterExpression(
                                FilterExpression.LogicalOperator.AND, predicates, null, null, null);
                var byId = new HashMap<UUID, DatasetFieldEntity>();
                fields.values().forEach(value -> byId.put(value.getId(), value));
                filterValidator.validate(dataset.getId(), filter, byId);
                metric.setFixedFilterExpression(write(filter));
            }
            metrics.add(metric);
        }

        // 目标键唯一性是无扇出查询的前提；外部查询不能占有平台检查点行锁。
        for (var definition : model.relationships()) {
            var from = required(datasets, definition.from());
            var to = required(datasets, definition.to());
            var fromField = physicalField(fields.values(), from.getId(), definition.fromColumn());
            var toField = physicalField(fields.values(), to.getId(), definition.toColumn());
            if (!compatible(fromField.getStarrocksType(), toField.getStarrocksType()))
                throw new IllegalArgumentException("Git relationship field types are incompatible");
            cardinality.validate(
                    new RelationshipCardinalityValidator.Endpoint(from, List.of(fromField)),
                    new RelationshipCardinalityValidator.Endpoint(to, List.of(toField)),
                    MANY_TO_ONE);
            var relationship = new DatasetRelationshipEntity();
            relationship.setId(identity(properties, RELATIONSHIP, definition.name(), ownerships));
            relationship.setName(definition.name());
            relationship.setSourceDatasetId(from.getId());
            relationship.setTargetDatasetId(to.getId());
            relationship.setCardinality(MANY_TO_ONE.name());
            relationship.setJoinType(definition.joinType());
            relationship.setFanoutSafe(true);
            relationship.setStatus(ACTIVE);
            relationship.setLastValidatedAt(LocalDateTime.now());
            relationship.setLastValidationStatus(VALID);
            relationships.add(relationship);
            var pair = new DatasetRelationshipFieldPairEntity();
            pair.setRelationshipId(relationship.getId());
            pair.setSourceFieldId(fromField.getId());
            pair.setTargetFieldId(toField.getId());
            pairs.add(pair);
        }
        return new GitPreparedSnapshot(
                write(model),
                List.copyOf(ownerships),
                List.copyOf(datasets.values()),
                List.copyOf(fields.values()),
                List.copyOf(dimensions),
                List.copyOf(metrics),
                List.copyOf(relationships),
                List.copyOf(pairs));
    }

    private static UUID identity(
            GitSyncProperties properties,
            GitObjectKind kind,
            String logical,
            List<GitObjectOwnershipEntity> ownerships) {
        var ownership = new GitObjectOwnershipEntity();
        ownership.setObjectId(id(properties, kind, logical));
        ownership.setRepositoryId(properties.getRepositoryId());
        ownership.setModelName(properties.getModelName());
        ownership.setKind(kind.table());
        ownership.setLogicalName(logical);
        ownerships.add(ownership);
        return ownership.getObjectId();
    }

    private static UUID id(GitSyncProperties properties, GitObjectKind kind, String logical) {
        // NUL 分隔作用域，避免拼接歧义；重试、软删除恢复和服务重启都得到相同身份。
        return UUID.nameUUIDFromBytes(
                String.join(
                                ID_SEPARATOR,
                                properties.getRepositoryId(),
                                properties.getModelName(),
                                kind.name(),
                                logical)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String dataset, String field) {
        return dataset + SEPARATOR + field;
    }

    private static String empty(String value) {
        return value == null ? EMPTY : value;
    }

    private static String display(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static <T> T required(Map<String, T> values, String key) {
        T value = values.get(key);
        if (value == null)
            throw new IllegalArgumentException("Git logical reference not found: " + key);
        return value;
    }

    private static DatasetFieldEntity physicalField(
            Collection<DatasetFieldEntity> fields, UUID dataset, String column) {
        return fields.stream()
                .filter(
                        value ->
                                value.getDatasetId().equals(dataset)
                                        && value.getPhysicalName().equals(column))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Git relationship column is absent"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Git definition could not be serialized", exception);
        }
    }

    private static String type(String value) {
        return value.toUpperCase(Locale.ROOT)
                .replaceAll(TYPE_PARAMETERS, EMPTY)
                .trim()
                .replaceAll(TYPE_SPACES, SPACE);
    }

    private static boolean compatible(String left, String right) {
        // 目标键原生唯一不保证隐式类型转换后唯一；尤其浮点转换会合并不同的大整数。
        // 只有规范化后的同类型连接才能复用目标原生 GROUP BY 的唯一性证明。
        return type(left).equals(type(right));
    }

    private static void validateLiteral(String physicalType, Object value) {
        String type = type(physicalType);
        if (NUMERIC.contains(type) && value instanceof Number
                || BOOLEAN.contains(type) && value instanceof Boolean
                || TEXT.contains(type) && value instanceof String) return;
        if (TEMPORAL.contains(type) && value instanceof String text) {
            try {
                if (DATE.equals(type)) LocalDate.parse(text);
                else if (ZONED_TEMPORAL.contains(type)) OffsetDateTime.parse(text);
                else LocalDateTime.parse(text);
                return;
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                        "Git temporal literal must be a valid ISO date/time", exception);
            }
        }
        throw new IllegalArgumentException("Git fixed literal does not match physical field type");
    }
}
