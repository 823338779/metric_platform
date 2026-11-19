package com.metricplatform.catalog;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricplatform.catalog.AssetGrantRepository.*;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 普通用户只能在实时生效构建内使用已授权资产。 */
@Service
@RequiredArgsConstructor
public class AssetAccessService {
    private static final String NATIVE_ID = "unique_id",
            NATIVE_KIND = "resource_type",
            METRIC_NODE = "metric",
            MODEL_NODE = "model",
            DIMENSION_NODE = "dimension",
            GRANTED = "granted",
            SELECTOR = "selector";

    /** 从服务核对当前部署及完整登记。 */
    private final DeployedAssets deployed;

    /** 当前租户的资产和授权关系。 */
    private final AssetGrantRepository store;

    /** 同步获取固定构建的合法维度选项。 */
    private final BuildGateway gateway;

    public record View(
            /** 平台稳定资产编号。 */
            UUID assetId,
            /** 所属管理者账号。 */
            UUID ownerId,
            /** 资产所在部署目标。 */
            DeploymentKey target,
            /** 目录中显示的执行环境名称。 */
            String environmentName,
            /** 资产类型。 */
            String kind,
            /** dbt 节点唯一标识。 */
            String nodeId,
            /** 语义模型内的维度名称。 */
            String dimensionName,
            /** 原生查询名或维度所属模型。 */
            String name,
            /** 数据集预览的精确选择器。 */
            String selector,
            /** 当前生效构建。 */
            UUID buildId,
            /** 查询列表所对应的账号具有该资产使用权（含所有者）。 */
            boolean granted) {}

    private GrantedQuery query(UUID build) {
        var target = store.target(build);
        if (!build.equals(deployed.current(target)))
            throw new ResponseStatusException(HttpStatus.GONE, "该部署版本已失效，请刷新资产列表");
        return new GrantedQuery(
                build, store.members(build, PlatformPrincipal.current().identityId()), gateway);
    }

    public QueryRequest check(UUID build, QueryRequest input) {
        var checked = query(build).check(input);
        // 获取合法维度可能较慢，完成解析后再次确认发布版本。
        if (!build.equals(deployed.live(store.target(build))))
            throw new ResponseStatusException(HttpStatus.GONE, "部署已切换，请刷新资产列表");
        return checked;
    }

    public AvailableDimensionsView dimensions(UUID build, AvailableDimensionsRequest input) {
        return query(build).dimensions(input);
    }

    public ArrayNode catalog(UUID build) {
        var actor = PlatformPrincipal.current();
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        // 管理者保留原生目录。指标与模型可直接使用，模型内维度从登记记录逐项补齐。
        if (actor.manager()) {
            for (var raw : gateway.catalog(build)) {
                ObjectNode node = raw.deepCopy();
                String kind = node.path(NATIVE_KIND).asText();
                if (METRIC_NODE.equals(kind) || MODEL_NODE.equals(kind)) node.put(GRANTED, true);
                rows.add(node);
            }
        }
        var members =
                actor.manager() ? store.members(build, actor.identityId()) : query(build).assets();
        // 只读目录仅公开定位资产与发起授权操作所需的字段，不返回源码。
        for (var asset : members) {
            var entry = asset.entry();
            boolean dimension = DeployedAssets.DIMENSION.equals(entry.kind());
            if (actor.manager() && !dimension) continue;
            var row = rows.addObject();
            row.put(NATIVE_ID, dimension ? asset.assetId().toString() : entry.nodeId());
            row.put(DeployedAssets.NAME, dimension ? entry.dimensionName() : entry.queryName());
            row.put(GRANTED, asset.granted());
            if (dimension) {
                row.put(NATIVE_KIND, DIMENSION_NODE);
                row.put(DeployedAssets.MODEL_NAME, entry.queryName());
            } else if (DeployedAssets.DATASET.equals(entry.kind())) {
                row.put(NATIVE_KIND, MODEL_NODE);
                if (asset.granted()) row.put(SELECTOR, entry.selector());
            } else {
                row.put(NATIVE_KIND, METRIC_NODE);
            }
        }
        return rows;
    }

    public List<Account> readers() {
        PlatformPrincipal.current().requireManager();
        return store.readers();
    }

    private void requireReader(UUID id) {
        PlatformPrincipal.current().requireManager();
        if (!store.hasReader(id)) throw new NoSuchElementException("用户不存在");
    }

    public List<View> list(UUID selectedReader) {
        var actor = PlatformPrincipal.current();
        if (selectedReader != null) requireReader(selectedReader);
        var user = selectedReader != null ? selectedReader : actor.identityId();
        // 授权状态实时关联当前账号，目录观察由后台维护。
        return store.list(user).stream().map(this::view).toList();
    }

    private View view(CatalogItem item) {
        var a = item.asset();
        var e = a.entry();
        return new View(
                a.assetId(),
                a.ownerId(),
                a.target(),
                item.environmentName(),
                e.kind(),
                e.nodeId(),
                e.dimensionName(),
                e.queryName(),
                e.selector(),
                item.build(),
                a.granted());
    }

    public void set(UUID reader, UUID asset, boolean grant) {
        requireReader(reader);
        if (grant) {
            var a = store.asset(reader, asset);
            UUID build = deployed.current(a.target());
            if (build == null
                    || store.members(build, reader).stream()
                            .noneMatch(row -> row.assetId().equals(asset)))
                throw new NoSuchElementException("资产当前不可用");
            store.grant(reader, asset);
        } else store.revoke(reader, asset);
    }
}
