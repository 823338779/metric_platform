package com.metricplatform.ontology;

import java.util.List;
import java.util.UUID;

public final class OntologyContracts {
    private OntologyContracts() {}

    public record Scope(String tenantId, UUID environmentId) {
        public Scope {
            if (tenantId == null || tenantId.isBlank() || environmentId == null) {
                throw new OntologyException(OntologyException.Code.INVALID, "本体必须归属租户和执行环境");
            }
        }
    }

    public record Topology(long revision, List<ObjectType> objects, List<Relation> relations) {}

    public record ObjectType(UUID id, String name, String description, UUID datasetAssetId,
                             UUID primaryPropertyId, List<Property> properties) {}

    public record Property(UUID id, String name, String columnName, String declaredType) {}

    public enum Cardinality { ONE_TO_ONE, MANY_TO_ONE, ONE_TO_MANY }

    public record Relation(UUID id, String name, String description, UUID sourceObjectId,
                           UUID sourcePropertyId, UUID targetObjectId, UUID targetPropertyId,
                           Cardinality cardinality) {}

    public enum BindingStatus { COMPATIBLE, UNVERIFIED, NEEDS_REPAIR }

    public record Finding(String kind, UUID id, BindingStatus status, String reason) {}

    public record BindingReport(UUID buildId, long revision, List<Finding> findings) {}

    public record Reference(UUID id, String name) {}
}
