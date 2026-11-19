package com.metricplatform.catalog;

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
            METRIC_NODE = "metric";

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
            /** 当前生效构建，休眠授权为空。 */
            UUID buildId,
            /** 选中账号持有该资产使用权。 */
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

    public List<Map<String, String>> catalog(UUID build) {
        // 普通用户工作台只需指标名称；不转发原生节点、依赖或项目源码。
        return query(build).readable().stream()
                .filter(a -> DeployedAssets.METRIC.equals(a.entry().kind()))
                .map(
                        a ->
                                Map.of(
                                        NATIVE_ID,
                                        a.entry().nodeId(),
                                        NATIVE_KIND,
                                        METRIC_NODE,
                                        DeployedAssets.NAME,
                                        a.entry().queryName()))
                .toList();
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
        // 后台负责自动登记，列表读取同时补齐新部署，避免等待下一次定时同步。
        List<DeploymentKey> targets;
        if (actor.manager()) {
            deployed.discover();
            targets = store.targets();
        } else {
            targets = store.granted(user).stream().map(Asset::target).distinct().toList();
        }
        var rows = new ArrayList<View>();
        Set<UUID> active = new HashSet<>();
        for (var target : targets) {
            UUID build = deployed.current(target);
            if (build == null) continue;
            var members = store.members(build, user);
            if (!actor.manager()) members = new GrantedQuery(build, members, gateway).readable();
            for (var a : members) {
                active.add(a.assetId());
                rows.add(view(a, build));
            }
        }
        if (selectedReader != null)
            for (var a : store.granted(user)) {
                if (!active.contains(a.assetId())) rows.add(view(a, null));
            }
        return rows;
    }

    private View view(Asset a, UUID build) {
        var e = a.entry();
        return new View(
                a.assetId(),
                a.ownerId(),
                a.target(),
                e.kind(),
                e.nodeId(),
                e.dimensionName(),
                e.queryName(),
                e.selector(),
                build,
                a.granted());
    }

    public void set(UUID reader, UUID asset, boolean grant) {
        requireReader(reader);
        var a = store.asset(reader, asset);
        if (grant) {
            UUID build = deployed.current(a.target());
            if (build == null
                    || store.members(build, reader).stream()
                            .noneMatch(row -> row.assetId().equals(asset)))
                throw new NoSuchElementException("资产当前不可用");
            store.grant(reader, asset);
        } else store.revoke(reader, asset);
    }
}
