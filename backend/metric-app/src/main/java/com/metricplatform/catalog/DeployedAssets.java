package com.metricplatform.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.AssetGrantRepository.Entry;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.environment.AssetOwnership;
import com.metricplatform.identity.TenantScope;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 从服务实际生效部署登记资产；调度和按需发现使用相同路径。 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class DeployedAssets {
    static final String METRIC = "METRIC", DATASET = "DATASET", DIMENSION = "DIMENSION";
    static final String NAME = "name", MODEL_NAME = "semanticModelName", OPTION_ID = "optionId";
    static final String EMPTY = "", FQN = "fqn:", DOT = ".";
    private static final String NATIVE_TYPE = "resource_type",
            NATIVE_ID = "unique_id",
            MODEL = "model",
            SEMANTIC = "semantic_model",
            NATIVE_METRIC = "metric",
            DIMENSIONS = "dimensions",
            PATH = "fqn";
    private static final Pattern EXACT_PART = Pattern.compile("[\\p{L}\\p{N}_-]+");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AssetGrantRepository store;
    private final AssetOwnership ownership;
    private final BuildGateway service;

    /** 后台只枚举已确认归属的构建；一个失效的历史构建或服务目标不能阻塞其他目标。 */
    @Scheduled(
            fixedDelayString = "${metric-platform.assets.sync-delay:60000}",
            initialDelayString = "${metric-platform.assets.sync-delay:60000}")
    public void reconcile() {
        for (String tenant : store.tenants()) {
            try (var scope = TenantScope.open(tenant)) {
                refreshCatalog();
            } catch (RuntimeException ex) {
                log.warn("租户资产发现待重试 {}", tenant, ex);
            }
        }
    }

    void refreshCatalog() {
        long sequence = store.nextScan();
        discover();
        var environments =
                store.targets().stream()
                        .collect(Collectors.groupingBy(DeploymentKey::environmentId));
        // 每轮对一个环境只发布一次观察，旧分支的空指针不能覆盖新分支的生效构建。
        environments.forEach(
                (environment, targets) -> {
                    UUID active = null;
                    boolean complete = true;
                    for (var target : targets) {
                        try {
                            active = current(target);
                            if (active != null) break;
                        } catch (RuntimeException ex) {
                            complete = false;
                            log.warn("资产登记待重试，环境 {}", environment, ex);
                        }
                    }
                    // 只用完整的否定结果清空观察，临时故障保留上次成功读取的目录。
                    if (active != null || complete) store.observe(environment, active, sequence);
                });
    }

    public void discover() {
        for (var owned : store.discover()) {
            try {
                var build = service.getBuild(owned.build());
                if (build != null
                        && owned.build().equals(build.buildId())
                        && build.execution() != null
                        && owned.environment().equals(build.execution().environmentId()))
                    store.bind(
                            owned.build(),
                            new DeploymentKey(
                                    build.repository(), owned.environment(), build.branchName()));
            } catch (BuildGateway.Failure ex) {
                log.warn("暂时无法读取已知构建 {}", owned.build());
            }
        }
    }

    UUID live(DeploymentKey key) {
        DeploymentTargetView current;
        try {
            current = service.current(key);
        } catch (BuildGateway.Failure failure) {
            if (failure.status() == 404) return null;
            throw failure;
        }
        if (!key.environmentId().equals(current.environmentId())
                || !key.branchName().equals(current.branchName())
                || !key.repository().equals(current.repository()))
            throw new IllegalStateException("部署目标不匹配");
        UUID build = current.activeBuildId();
        if (build == null || !ownership.hasBuild(TenantScope.require(), key.environmentId(), build))
            return null;
        return build;
    }

    public UUID current(DeploymentKey key) {
        UUID build = live(key);
        if (build == null) return null;
        store.bind(build, key);
        if (!key.equals(store.target(build))) throw new IllegalStateException("生效构建归属不一致");
        if (!store.ready(build)) {
            store.register(build, key, entries(build));
            // 首次登记可能读取多个语义模型；登记期间切换的构建不得作为当前资源返回。
            if (!build.equals(live(key)))
                throw new ResponseStatusException(HttpStatus.GONE, "部署已切换，请刷新资产列表");
        }
        return build;
    }

    /** 使用 dbt 原生节点与模型内维度名称，缺少完整目录时不提交登记。 */
    private List<Entry> entries(UUID build) {
        var catalog = service.catalog(build);
        if (catalog == null || !catalog.isArray()) throw new IllegalStateException("缺少构建目录");
        var entries = new ArrayList<Entry>();
        for (var node : catalog) {
            String kind = required(node, NATIVE_TYPE);
            if (!Set.of(MODEL, SEMANTIC, NATIVE_METRIC).contains(kind)) continue;
            String id = required(node, NATIVE_ID), name = required(node, NAME);
            switch (kind) {
                case NATIVE_METRIC -> entries.add(new Entry(METRIC, id, EMPTY, name, EMPTY));
                case MODEL -> {
                    JsonNode parts = node.path(PATH);
                    if (!parts.isArray()) parts = detail(build, id).path(PATH);
                    List<String> path = new ArrayList<>();
                    for (var part : parts) {
                        if (!part.isTextual() || !EXACT_PART.matcher(part.textValue()).matches())
                            throw new IllegalStateException("数据集缺少精确预览路径");
                        path.add(part.textValue());
                    }
                    if (path.isEmpty()) throw new IllegalStateException("数据集缺少预览路径");
                    entries.add(new Entry(DATASET, id, EMPTY, name, FQN + String.join(DOT, path)));
                }
                case SEMANTIC -> {
                    var dimensions = node.path(DIMENSIONS);
                    if (!dimensions.isArray()) dimensions = detail(build, id).path(DIMENSIONS);
                    if (!dimensions.isArray()) throw new IllegalStateException("语义模型缺少维度定义");
                    for (var dimension : dimensions)
                        entries.add(
                                new Entry(DIMENSION, id, required(dimension, NAME), name, EMPTY));
                }
                default -> throw new IllegalStateException("未知资源类型");
            }
        }
        // 不同包可声明同名节点。按环境授权的稳定身份无法区分它们，只登记无歧义的成员。
        var counts = new HashMap<List<String>, Integer>();
        for (var entry : entries) counts.merge(assetKey(entry), 1, Integer::sum);
        return entries.stream().filter(entry -> counts.get(assetKey(entry)) == 1).toList();
    }

    private static List<String> assetKey(Entry entry) {
        return List.of(entry.kind(), entry.queryName(), entry.dimensionName());
    }

    private JsonNode detail(UUID build, String id) {
        var detail = service.resource(build, id);
        if (!build.equals(detail.buildId()) || !id.equals(detail.resourceId()))
            throw new IllegalStateException("资源身份不匹配");
        return JSON.valueToTree(detail.resource());
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isBlank()) throw new IllegalStateException("资产缺少必要标识");
        return value;
    }
}
