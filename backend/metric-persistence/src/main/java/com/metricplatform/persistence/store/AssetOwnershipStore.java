package com.metricplatform.persistence.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.metricplatform.environment.AssetOwnership;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.entity.BuildOwnershipEntity;
import com.metricplatform.persistence.mapper.AssetOwnershipMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AssetOwnershipStore implements AssetOwnership {
    private final AssetOwnershipMapper ownership;

    public boolean hasEnvironment(String tenant, UUID environment) {
        TenantScope.require(tenant);
        return ownership.hasEnvironment(tenant, environment);
    }

    public boolean hasBuild(String tenant, UUID build) {
        TenantScope.require(tenant);
        return ownership.exists(
                Wrappers.<BuildOwnershipEntity>lambdaQuery()
                        .eq(BuildOwnershipEntity::getBuildId, build));
    }

    public boolean hasBuild(String tenant, UUID environment, UUID build) {
        TenantScope.require(tenant);
        return ownership.exists(
                Wrappers.<BuildOwnershipEntity>lambdaQuery()
                        .eq(BuildOwnershipEntity::getBuildId, build)
                        .eq(BuildOwnershipEntity::getEnvironmentId, environment));
    }

    public UUID cursorSecret(String tenant) {
        TenantScope.require(tenant);
        return ownership.cursorSecret();
    }
}
