package com.metricplatform.persistence.store;

import com.metricplatform.catalog.AssetGrantRepository;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.mapper.AssetGrantMapper;
import com.metricplatform.persistence.mapper.AssetGrantMapper.AssetRow;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** 仅存储授权身份和固定构建成员；当前发布事实由服务提供。 */
@Repository
@RequiredArgsConstructor
public class AssetGrantStore implements AssetGrantRepository {
    private static final int MEMBER_BATCH_SIZE = 200;
    private final AssetGrantMapper mapper;

    /** 调度器只枚举租户编号，各租户的资产读写必须绑定对应的作用域。 */
    public List<String> tenants() {
        TenantScope.requireUnbound();
        return mapper.tenants();
    }

    public List<OwnedBuild> discover() {
        return mapper.discover(TenantScope.require());
    }

    public void bind(UUID build, DeploymentKey target) {
        mapper.bind(TenantScope.require(), build, target);
    }

    public List<DeploymentKey> targets() {
        return mapper.targets(TenantScope.require());
    }

    public DeploymentKey target(UUID build) {
        return mapper.target(TenantScope.require(), build).orElseThrow();
    }

    public boolean ready(UUID build) {
        return mapper.indexed(TenantScope.require(), build).orElse(false);
    }

    /** 锁住固定构建后一次写入所有成员；回滚、并发重试及空目录均不会留下半份登记。 */
    @Transactional
    public void register(UUID build, DeploymentKey target, List<Entry> entries) {
        String tenant = TenantScope.require();
        if (mapper.lockIndex(tenant, build)
                .orElseThrow(() -> new EmptyResultDataAccessException(1))) {
            return;
        }
        if (!target.equals(target(build))) throw new IllegalStateException("构建目标不匹配");
        for (int start = 0; start < entries.size(); start += MEMBER_BATCH_SIZE) {
            var batch = entries.subList(start, Math.min(start + MEMBER_BATCH_SIZE, entries.size()));
            mapper.insertMembers(tenant, build, target.environmentId(), batch);
        }
        mapper.finishIndex(tenant, build);
    }

    public List<Asset> members(UUID build, UUID reader) {
        return mapper.members(TenantScope.require(), build, reader).stream()
                .map(AssetRow::toAsset)
                .toList();
    }

    public long nextScan() {
        TenantScope.require();
        return mapper.nextScan();
    }

    public void observe(UUID environment, UUID build, long sequence) {
        mapper.observe(TenantScope.require(), environment, build, sequence);
    }

    public List<CatalogItem> list(UUID reader) {
        return mapper.list(TenantScope.require(), reader).stream()
                .map(AssetRow::toCatalogItem)
                .toList();
    }

    public Asset asset(UUID reader, UUID asset) {
        return mapper.asset(TenantScope.require(), reader, asset).orElseThrow().toAsset();
    }

    public boolean hasReader(UUID reader) {
        return mapper.hasReader(TenantScope.require(), reader);
    }

    public List<Account> readers() {
        return mapper.readers(TenantScope.require());
    }

    public void grant(UUID reader, UUID asset) {
        mapper.grant(TenantScope.require(), reader, asset);
    }

    public void revoke(UUID reader, UUID asset) {
        if (!mapper.revoke(TenantScope.require(), reader, asset)) {
            throw new NoSuchElementException("资产不存在");
        }
    }
}
