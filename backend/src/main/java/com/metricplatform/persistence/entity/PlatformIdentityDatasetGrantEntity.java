package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("platform_identity_dataset_grant")
public class PlatformIdentityDatasetGrantEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private UUID tenantId;
    private UUID identityId;
    private UUID datasetId;
    private boolean discoverAllowed;
    private boolean queryAllowed;
}
