package com.metricplatform.catalog;

import static com.metricplatform.catalog.DeployedAssets.*;

import com.metricplatform.catalog.AssetGrantRepository.Asset;
import com.metricplatform.catalog.BuildContracts.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** 在完整的固定构建成员集合内消歧，再检查用户授权。 */
record GrantedQuery(UUID build, List<Asset> assets, BuildGateway gateway) {
    private static final String TIME = "metric_time";
    private static final String DENIED = "无权限使用所选指标或维度";

    /** 在授权过滤前消歧，避免同名的未授权资源被过滤后产生虚假的唯一匹配。 */
    List<Asset> readable() {
        var counts =
                assets.stream()
                        .collect(
                                Collectors.groupingBy(
                                        GrantedQuery::queryKey, Collectors.counting()));
        return assets.stream()
                .filter(Asset::granted)
                .filter(a -> counts.get(queryKey(a)) == 1)
                .toList();
    }

    private static List<String> queryKey(Asset a) {
        var e = a.entry();
        return DATASET.equals(e.kind())
                ? List.of(DATASET, e.selector())
                : List.of(e.kind(), e.queryName(), e.dimensionName());
    }

    private boolean allowed(String kind, Predicate<Asset> matches) {
        var found =
                assets.stream()
                        .filter(a -> kind.equals(a.entry().kind()))
                        .filter(matches)
                        .limit(2)
                        .toList();
        return found.size() == 1 && found.getFirst().granted();
    }

    private void metrics(List<String> names) {
        if (names == null
                || names.isEmpty()
                || names.stream()
                        .anyMatch(n -> !allowed(METRIC, a -> a.entry().queryName().equals(n))))
            throw new SecurityException(DENIED);
    }

    AvailableDimensionsView dimensions(AvailableDimensionsRequest request) {
        metrics(request.metrics());
        var result = gateway.availableDimensions(build, request);
        if (!build.equals(result.buildId())
                || !new HashSet<>(request.metrics()).equals(new HashSet<>(result.metrics())))
            throw new IllegalStateException("维度选项上下文不匹配");
        // optionId 只在当前指标集合内有效；缺少唯一模型归属的选项不可授权。
        Map<String, Integer> counts = new HashMap<>();
        for (var option : result.dimensions()) {
            if (option.get(OPTION_ID) instanceof String id && !id.isBlank())
                counts.merge(id, 1, Integer::sum);
        }
        var options =
                result.dimensions().stream()
                        .filter(o -> counts.getOrDefault(o.get(OPTION_ID), 0) == 1)
                        .filter(
                                o ->
                                        allowed(
                                                DIMENSION,
                                                a ->
                                                        a.entry()
                                                                        .queryName()
                                                                        .equals(o.get(MODEL_NAME))
                                                                && a.entry()
                                                                        .dimensionName()
                                                                        .equals(o.get(NAME))))
                        .toList();
        return new AvailableDimensionsView(build, result.metrics(), options);
    }

    QueryRequest check(QueryRequest q) {
        if (q.mode() == QueryMode.PREVIEW) {
            var matches =
                    assets.stream()
                            .filter(a -> DATASET.equals(a.entry().kind()))
                            .filter(
                                    a ->
                                            a.entry().selector().equals(q.selector())
                                                    || a.entry().queryName().equals(q.selector()))
                            .toList();
            if (matches.size() != 1 || !readable().contains(matches.getFirst()))
                throw new SecurityException("无权限查看所选数据集明细");
            // 只向服务发送登记过的精确选择器，避免客户端选择表达式扩大预览范围。
            return new QueryRequest(
                    q.mode(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    q.limit(),
                    matches.getFirst().entry().selector(),
                    null);
        }
        metrics(q.metrics());
        Set<String> needed = new HashSet<>();
        if (q.groupBy() != null)
            for (var item : q.groupBy()) needed.add(item == null ? null : item.optionId());
        if (q.filters() != null)
            for (var item : q.filters()) needed.add(item == null ? null : item.optionId());
        if (q.mode() == QueryMode.DIMENSION_VALUES) needed.add(q.dimensionOptionId());
        if (q.orderBy() != null)
            for (var item : q.orderBy()) {
                String id = item == null ? null : item.fieldId();
                if (!q.metrics().contains(id)) needed.add(id);
            }
        boolean timeRange = q.startTime() != null || q.endTime() != null;
        if (!needed.isEmpty() || timeRange) {
            var options = dimensions(new AvailableDimensionsRequest(q.metrics())).dimensions();
            Set<Object> ids = new HashSet<>();
            for (var option : options) ids.add(option.get(OPTION_ID));
            if (!ids.containsAll(needed)
                    || timeRange && options.stream().noneMatch(o -> TIME.equals(o.get(NAME))))
                throw new SecurityException(DENIED);
        }
        return q;
    }
}
