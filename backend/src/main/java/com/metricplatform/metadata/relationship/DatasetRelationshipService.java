package com.metricplatform.metadata.relationship;

import static com.metricplatform.metadata.relationship.RelationshipDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipFieldPairEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipFieldPairMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatasetRelationshipService {
    private final DatasetMapper datasets;
    private final DatasetFieldMapper fields;
    private final DatasetRelationshipMapper relationships;
    private final DatasetRelationshipFieldPairMapper pairs;
    private final RelationshipCardinalityValidator cardinalityValidator;

    @Transactional
    public RelationshipResponse create(CreateRelationshipRequest request) {
        DatasetEntity source = dataset(request.sourceDatasetId());
        DatasetEntity target = dataset(request.targetDatasetId());
        ResolvedPairs resolved =
                validateDefinition(source, target, request.cardinality(), request.fieldPairs());
        cardinalityValidator.validate(resolved.source(), resolved.target(), request.cardinality());

        LocalDateTime now = LocalDateTime.now();
        DatasetRelationshipEntity relationship = new DatasetRelationshipEntity();
        relationship.setId(UUID.randomUUID());
        relationship.setName(request.name().trim());
        relationship.setSourceDatasetId(source.getId());
        relationship.setTargetDatasetId(target.getId());
        relationship.setCardinality(request.cardinality().name());
        applyMetadata(relationship, source, target, request.description(), request.displayPath(),
                request.semanticRole(), request.fanoutSafe(), request.status());
        relationship.setLastValidationStatus("VALID");
        relationship.setLastValidatedAt(now);
        relationships.insert(relationship);
        insertPairs(relationship.getId(), request.fieldPairs());
        return response(relationship, request.fieldPairs());
    }

    public List<RelationshipResponse> list(UUID datasetId) {
        LambdaQueryWrapper<DatasetRelationshipEntity> query = new LambdaQueryWrapper<>();
        if (datasetId != null) {
            query.and(
                    value ->
                            value.eq(DatasetRelationshipEntity::getSourceDatasetId, datasetId)
                                    .or()
                                    .eq(DatasetRelationshipEntity::getTargetDatasetId, datasetId));
        }
        query.orderByAsc(DatasetRelationshipEntity::getName);
        return relationships.selectList(query).stream().map(this::response).toList();
    }

    public RelationshipResponse get(UUID id) {
        return response(relationship(id));
    }

    @Transactional
    public RelationshipResponse update(UUID id, UpdateRelationshipRequest request) {
        DatasetRelationshipEntity relationship = relationship(id);
        if (relationship.getVersion() != request.version()) {
            throw new ConflictException("dataset relationship version is stale");
        }
        DatasetEntity source = dataset(relationship.getSourceDatasetId());
        DatasetEntity target = dataset(relationship.getTargetDatasetId());
        ResolvedPairs resolved =
                validateDefinition(source, target, request.cardinality(), request.fieldPairs());
        cardinalityValidator.validate(resolved.source(), resolved.target(), request.cardinality());

        relationship.setName(request.name().trim());
        relationship.setCardinality(request.cardinality().name());
        applyMetadata(relationship, source, target, request.description(), request.displayPath(),
                request.semanticRole(), request.fanoutSafe(), request.status());
        relationship.setLastValidationStatus("VALID");
        relationship.setLastValidatedAt(LocalDateTime.now());
        if (relationships.updateById(relationship) == 0) {
            throw new ConflictException("dataset relationship version is stale");
        }
        pairs.deleteByRelationshipId(id);
        insertPairs(id, request.fieldPairs());
        return response(relationship, request.fieldPairs());
    }

    @Transactional
    public RelationshipResponse revalidate(UUID id) {
        DatasetRelationshipEntity relationship = relationship(id);
        DatasetEntity source = dataset(relationship.getSourceDatasetId());
        DatasetEntity target = dataset(relationship.getTargetDatasetId());
        List<FieldPairRequest> fieldPairs = pairRequests(id);
        Cardinality cardinality = Cardinality.valueOf(relationship.getCardinality());
        ResolvedPairs resolved = validateDefinition(source, target, cardinality, fieldPairs);
        cardinalityValidator.validate(resolved.source(), resolved.target(), cardinality);
        relationship.setLastValidationStatus("VALID");
        relationship.setLastValidatedAt(LocalDateTime.now());
        if (relationships.updateById(relationship) == 0) {
            throw new ConflictException("dataset relationship changed during validation");
        }
        return response(relationship, fieldPairs);
    }

    @Transactional
    public void delete(UUID id) {
        relationship(id);
        pairs.deleteByRelationshipId(id);
        relationships.softDeleteById(id);
    }

    private ResolvedPairs validateDefinition(
            DatasetEntity source,
            DatasetEntity target,
            Cardinality cardinality,
            List<FieldPairRequest> requestedPairs) {
        if (source.getId().equals(target.getId())) {
            throw new IllegalArgumentException("relationship endpoints must be different datasets");
        }
        if (!source.getDataSourceId().equals(target.getDataSourceId())) {
            throw new IllegalArgumentException(
                    "relationship datasets must use the same data source");
        }
        if (cardinality == null) {
            throw new IllegalArgumentException("relationship cardinality is required");
        }
        if (requestedPairs == null || requestedPairs.isEmpty()) {
            throw new IllegalArgumentException("relationship requires at least one field pair");
        }
        Set<UUID> sourceIds = new HashSet<>();
        Set<UUID> targetIds = new HashSet<>();
        List<DatasetFieldEntity> sourceFields = new ArrayList<>();
        List<DatasetFieldEntity> targetFields = new ArrayList<>();
        for (FieldPairRequest pair : requestedPairs) {
            if (pair == null || pair.sourceFieldId() == null || pair.targetFieldId() == null) {
                throw new IllegalArgumentException("relationship field pair is incomplete");
            }
            if (!sourceIds.add(pair.sourceFieldId()) || !targetIds.add(pair.targetFieldId())) {
                throw new IllegalArgumentException(
                        "relationship field pairs contain duplicate fields");
            }
            DatasetFieldEntity sourceField = field(pair.sourceFieldId(), "source");
            DatasetFieldEntity targetField = field(pair.targetFieldId(), "target");
            if (!source.getId().equals(sourceField.getDatasetId())) {
                throw new IllegalArgumentException(
                        "source field does not belong to source dataset");
            }
            if (!target.getId().equals(targetField.getDatasetId())) {
                throw new IllegalArgumentException(
                        "target field does not belong to target dataset");
            }
            if (!sourceField.isSelected() || !targetField.isSelected()) {
                throw new IllegalArgumentException(
                        "relationship fields must be selected dataset fields");
            }
            if (!compatible(sourceField.getStarrocksType(), targetField.getStarrocksType())) {
                throw new IllegalArgumentException("relationship field types are incompatible");
            }
            sourceFields.add(sourceField);
            targetFields.add(targetField);
        }
        return new ResolvedPairs(
                new RelationshipCardinalityValidator.Endpoint(source, sourceFields),
                new RelationshipCardinalityValidator.Endpoint(target, targetFields));
    }

    private static boolean compatible(String left, String right) {
        String leftBase = baseType(left);
        String rightBase = baseType(right);
        if (leftBase.equals(rightBase)) {
            return true;
        }
        Set<String> numeric =
                Set.of(
                        "TINYINT",
                        "SMALLINT",
                        "INT",
                        "INTEGER",
                        "BIGINT",
                        "LARGEINT",
                        "FLOAT",
                        "DOUBLE",
                        "DECIMAL",
                        "DECIMALV2",
                        "DECIMAL32",
                        "DECIMAL64",
                        "DECIMAL128");
        Set<String> text = Set.of("CHAR", "VARCHAR", "STRING");
        Set<String> temporal = Set.of("DATE", "DATETIME", "TIMESTAMP");
        return numeric.contains(leftBase) && numeric.contains(rightBase)
                || text.contains(leftBase) && text.contains(rightBase)
                || temporal.contains(leftBase) && temporal.contains(rightBase);
    }

    private static String baseType(String type) {
        if (type == null) {
            return "";
        }
        int parameters = type.indexOf('(');
        return (parameters < 0 ? type : type.substring(0, parameters))
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private DatasetEntity dataset(UUID id) {
        return java.util.Optional.ofNullable(datasets.selectById(id))
                .orElseThrow(() -> new NoSuchElementException("dataset not found: " + id));
    }

    private DatasetFieldEntity field(UUID id, String endpoint) {
        return java.util.Optional.ofNullable(fields.selectById(id))
                .orElseThrow(
                        () -> new NoSuchElementException(endpoint + " field not found: " + id));
    }

    private DatasetRelationshipEntity relationship(UUID id) {
        return java.util.Optional.ofNullable(relationships.selectById(id))
                .orElseThrow(
                        () -> new NoSuchElementException("dataset relationship not found: " + id));
    }

    private void insertPairs(UUID relationshipId, List<FieldPairRequest> requests) {
        for (int index = 0; index < requests.size(); index++) {
            FieldPairRequest request = requests.get(index);
            DatasetRelationshipFieldPairEntity pair = new DatasetRelationshipFieldPairEntity();
            pair.setRelationshipId(relationshipId);
            pair.setOrdinal(index);
            pair.setSourceFieldId(request.sourceFieldId());
            pair.setTargetFieldId(request.targetFieldId());
            pairs.insertPair(pair);
        }
    }

    private RelationshipResponse response(DatasetRelationshipEntity relationship) {
        return response(relationship, pairRequests(relationship.getId()));
    }

    private RelationshipResponse response(
            DatasetRelationshipEntity relationship, List<FieldPairRequest> requests) {
        return new RelationshipResponse(
                relationship.getId(),
                relationship.getName(),
                relationship.getSourceDatasetId(),
                relationship.getTargetDatasetId(),
                Cardinality.valueOf(relationship.getCardinality()),
                "LEFT",
                List.copyOf(requests),
                relationship.getLastValidationStatus(),
                relationship.getLastValidatedAt(),
                relationship.getVersion(),
                relationship.getDescription(),
                relationship.getDisplayPath(),
                relationship.getSemanticRole(),
                relationship.isFanoutSafe(),
                relationship.getStatus());
    }

    private static void applyMetadata(
            DatasetRelationshipEntity relationship,
            DatasetEntity source,
            DatasetEntity target,
            String description,
            String displayPath,
            String semanticRole,
            Boolean fanoutSafe,
            String status) {
        relationship.setDescription(trimToNull(description));
        relationship.setDisplayPath(
                displayPath == null || displayPath.isBlank()
                        ? source.getName() + " -> " + target.getName()
                        : displayPath.trim());
        relationship.setSemanticRole(trimToNull(semanticRole));
        relationship.setFanoutSafe(fanoutSafe == null || fanoutSafe);
        relationship.setStatus(status == null || status.isBlank() ? "ACTIVE" : status.trim());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<FieldPairRequest> pairRequests(UUID relationshipId) {
        return pairs.selectByRelationshipId(relationshipId).stream()
                .map(pair -> new FieldPairRequest(pair.getSourceFieldId(), pair.getTargetFieldId()))
                .toList();
    }

    private record ResolvedPairs(
            RelationshipCardinalityValidator.Endpoint source,
            RelationshipCardinalityValidator.Endpoint target) {}
}
