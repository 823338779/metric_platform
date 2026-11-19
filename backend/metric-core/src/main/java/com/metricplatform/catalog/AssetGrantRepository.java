package com.metricplatform.catalog;

import com.metricplatform.catalog.BuildContracts.DeploymentKey;

import java.util.List;
import java.util.UUID;

/** 持久化稳定资产身份、固定构建成员与用户使用权。 */
public interface AssetGrantRepository {
    record Entry(
            /** 指标、维度或数据集。 */
            String kind,
            /** 稳定 dbt 节点身份。 */
            String nodeId,
            /** 模型内维度名称，其他类型为空。 */
            String dimensionName,
            /** 指标名、模型名或所属语义模型名。 */
            String queryName,
            /** 数据集唯一预览路径。 */
            String selector) {}

    record Asset(
            /** 资产稳定主键。 */
            UUID assetId,
            /** 所属管理者账号。 */
            UUID ownerId,
            /** 租户内的部署范围。 */
            DeploymentKey target,
            /** 构建内的资源身份和选择器。 */
            Entry entry,
            /** 指定用户是否获得显式授权。 */
            boolean granted) {}

    record Account(
            /** 账号身份。 */
            UUID id,
            /** 租户账号登录名。 */
            String username) {}

    record OwnedBuild(
            /** 待发现目标的构建。 */
            UUID build,
            /** 执行环境。 */
            UUID environment) {}

    List<String> tenants();

    List<OwnedBuild> discover();

    void bind(UUID build, DeploymentKey target);

    List<DeploymentKey> targets();

    DeploymentKey target(UUID build);

    boolean ready(UUID build);

    void register(UUID build, DeploymentKey target, List<Entry> entries);

    List<Asset> members(UUID build, UUID reader);

    List<Asset> granted(UUID reader);

    Asset asset(UUID reader, UUID asset);

    List<Account> readers();

    boolean hasReader(UUID reader);

    void grant(UUID reader, UUID asset);

    void revoke(UUID reader, UUID asset);
}
