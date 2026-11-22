package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.catalog.AssetGrantRepository.Account;
import com.metricplatform.catalog.AssetGrantRepository.Asset;
import com.metricplatform.catalog.AssetGrantRepository.CatalogItem;
import com.metricplatform.catalog.AssetGrantRepository.Entry;
import com.metricplatform.catalog.AssetGrantRepository.OwnedBuild;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.persistence.entity.AssetUserGrantEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface AssetGrantMapper extends BaseMapper<AssetUserGrantEntity> {
    @InterceptorIgnore(tenantLine = "true")
    List<String> tenants();

    @InterceptorIgnore(tenantLine = "true")
    List<OwnedBuild> discover(@Param("tenant") String tenant);

    @InterceptorIgnore(tenantLine = "true")
    void bind(
            @Param("tenant") String tenant,
            @Param("build") UUID build,
            @Param("target") DeploymentKey target);

    List<DeploymentKey> targets(@Param("tenant") String tenant);

    Optional<String> datasetName(
            @Param("tenant") String tenant,
            @Param("environment") UUID environment,
            @Param("asset") UUID asset);

    Optional<DeploymentKey> target(@Param("tenant") String tenant, @Param("build") UUID build);

    Optional<Boolean> indexed(@Param("tenant") String tenant, @Param("build") UUID build);

    Optional<Boolean> lockIndex(@Param("tenant") String tenant, @Param("build") UUID build);

    @InterceptorIgnore(tenantLine = "true")
    void insertMembers(
            @Param("tenant") String tenant,
            @Param("build") UUID build,
            @Param("environment") UUID environment,
            @Param("entries") List<Entry> entries);

    void finishIndex(@Param("tenant") String tenant, @Param("build") UUID build);

    @InterceptorIgnore(tenantLine = "true")
    List<AssetRow> members(
            @Param("tenant") String tenant,
            @Param("build") UUID build,
            @Param("reader") UUID reader);

    long nextScan();

    @InterceptorIgnore(tenantLine = "true")
    void observe(
            @Param("tenant") String tenant,
            @Param("environment") UUID environment,
            @Param("build") UUID build,
            @Param("sequence") long sequence);

    @InterceptorIgnore(tenantLine = "true")
    List<AssetRow> list(@Param("tenant") String tenant, @Param("reader") UUID reader);

    @InterceptorIgnore(tenantLine = "true")
    Optional<AssetRow> asset(
            @Param("tenant") String tenant,
            @Param("reader") UUID reader,
            @Param("asset") UUID asset);

    @InterceptorIgnore(tenantLine = "true")
    boolean hasReader(@Param("tenant") String tenant, @Param("reader") UUID reader);

    @InterceptorIgnore(tenantLine = "true")
    List<Account> readers(@Param("tenant") String tenant);

    @InterceptorIgnore(tenantLine = "true")
    void grant(
            @Param("tenant") String tenant,
            @Param("reader") UUID reader,
            @Param("asset") UUID asset);

    @InterceptorIgnore(tenantLine = "true")
    boolean revoke(
            @Param("tenant") String tenant,
            @Param("reader") UUID reader,
            @Param("asset") UUID asset);

    record AssetRow(
            UUID assetId,
            UUID ownerId,
            String repository,
            UUID environmentId,
            String branchName,
            String kind,
            String nodeId,
            String dimensionName,
            String queryName,
            String selector,
            boolean granted,
            UUID build,
            String environmentName) {
        public Asset toAsset() {
            return new Asset(
                    assetId,
                    ownerId,
                    new DeploymentKey(repository, environmentId, branchName),
                    new Entry(kind, nodeId, dimensionName, queryName, selector),
                    granted);
        }

        public CatalogItem toCatalogItem() {
            return new CatalogItem(toAsset(), build, environmentName);
        }
    }
}
