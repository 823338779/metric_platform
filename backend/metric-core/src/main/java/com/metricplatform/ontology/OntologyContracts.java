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

    public record Property(UUID id, String name, String columnName, String declaredType,
                           @com.fasterxml.jackson.annotation.JsonIgnore PropertyEvidence evidence) {
        public Property(UUID id, String name, String columnName, String declaredType) {
            this(id, name, columnName, declaredType, null);
        }
    }

    /** 最近一次从物理表取得的完整类型、UTC 观察时间和当时的活动构建。 */
    public record ColumnObservation(String physicalType, String observedAt, UUID buildId,
                                    WarehouseColumns.TableTarget table) {}

    /** reason 描述本次结果；临时故障期间保留最后成功记录和上次已确认的冲突。 */
    public record PropertyEvidence(String acceptedPhysicalType, ColumnObservation lastObservation,
                                   String reason, String knownConflict) {
        public PropertyEvidence(String baseline, ColumnObservation last, String reason) {
            this(baseline, last, reason, null);
        }
    }

    /** 页面上确认的旧基准、新实测类型及其所属属性。 */
    public record TypeAcceptance(UUID propertyId, String expectedBaselineType, String observedType) {}

    /** expectedPrevious 是生成此次观察前读取的完整基准、成功历史与状态，用于条件更新。 */
    public record EvidenceUpdate(UUID propertyId, PropertyEvidence expectedPrevious, PropertyEvidence value) {}

    /** 序号在外部 I/O 之前分配；部署检查在图事务提交前执行。 */
    public record ObservationWrite(long sequence, List<EvidenceUpdate> updates, Runnable checkDeployment) {}

    /** 关联端点的声明、当前证据来源、比较基准与最后成功观察。 */
    public record EndpointEvidence(String columnName, String declaredType, String typeSource,
                                   String acceptedPhysicalType, ColumnObservation lastObservation, String reason) {}

    public enum Cardinality { ONE_TO_ONE, MANY_TO_ONE, ONE_TO_MANY }

    public record Relation(UUID id, String name, String description, UUID sourceObjectId,
                           UUID sourcePropertyId, UUID targetObjectId, UUID targetPropertyId,
                           Cardinality cardinality) {}

    public enum BindingStatus { COMPATIBLE, UNVERIFIED, NEEDS_REPAIR }

    public record Finding(String kind, UUID id, BindingStatus status, String reason,
                          EndpointEvidence evidence, EndpointEvidence sourceEvidence, EndpointEvidence targetEvidence) {
        public Finding(String kind, UUID id, BindingStatus status, String reason) {
            this(kind,id,status,reason,null,null,null);
        }
    }

    public record BindingReport(UUID buildId, long revision, List<Finding> findings,int formatVersion,String policyVersion,long deploymentVersion) {
        public BindingReport(UUID buildId,long revision,List<Finding> findings,int formatVersion,String policyVersion) {
            this(buildId,revision,findings,formatVersion,policyVersion,0);
        }
        public BindingReport(UUID buildId,long revision,List<Finding> findings) {
            this(buildId,revision,findings,2,"off");
        }
    }

    public record Reference(UUID id, String name) {}
}
