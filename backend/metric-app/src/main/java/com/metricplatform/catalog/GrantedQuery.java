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
    private static final String DIMENSION_TYPE = "dimensionType",
            TIME_TYPE = "time",
            GRANULARITIES = "granularities";
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
        return new AvailableDimensionsView(build, result.metrics(), allowedOptions(result));
    }

    List<Map<String, Object>> allowedOptions(AvailableDimensionsView result) {
        // optionId 只在当前指标集合内有效；系统时间由引擎提供，业务维度按所属模型逐项授权。
        Map<String, Integer> counts = new HashMap<>();
        for (var option : result.dimensions()) {
            if (option.get(OPTION_ID) instanceof String id && !id.isBlank())
                counts.merge(id, 1, Integer::sum);
        }
        return result.dimensions().stream()
                .filter(o -> counts.getOrDefault(o.get(OPTION_ID), 0) == 1)
                .filter(
                        o ->
                                (TIME.equals(o.get(NAME)) && o.get(MODEL_NAME) == null)
                                        || allowed(
                                                DIMENSION,
                                                a ->
                                                        a.entry()
                                                                        .queryName()
                                                                        .equals(o.get(MODEL_NAME))
                                                                && a.entry()
                                                                        .dimensionName()
                                                                        .equals(o.get(NAME))))
                .toList();
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
        Set<String> boundNames = new HashSet<>();
        Map<String, Map<String, Map<String, Object>>> perMetric = new HashMap<>();
        if (q.groupBy() != null)
            for (var item : q.groupBy()) {
                if (item instanceof BoundDimension bound) {
                    checkBindings(bound, perMetric);
                    boundNames.add(bound.name());
                } else if (item instanceof SelectedDimension shared) needed.add(shared.optionId());
                else throw new SecurityException(DENIED);
            }
        if (q.filters() != null)
            for (var item : q.filters()) needed.add(item == null ? null : item.optionId());
        if (q.mode() == QueryMode.DIMENSION_VALUES) needed.add(q.dimensionOptionId());
        if (q.orderBy() != null)
            for (var item : q.orderBy()) {
                String id = item == null ? null : item.fieldId();
                if (!q.metrics().contains(id) && !boundNames.contains(id)) needed.add(id);
            }
        if (!needed.isEmpty()) {
            var options = dimensions(new AvailableDimensionsRequest(q.metrics())).dimensions();
            Set<Object> ids = new HashSet<>();
            for (var option : options) ids.add(option.get(OPTION_ID));
            if (!ids.containsAll(needed)) throw new SecurityException(DENIED);
        }
        return q;
    }

    private void checkBindings(
            BoundDimension bound, Map<String, Map<String, Map<String, Object>>> perMetric) {
        String alignedType = null, alignedGrain = null;
        for (var entry : bound.bindings().entrySet()) {
            // 单指标选项只能在同一构建的该指标内解释。请求内复用选项以支持多个绑定分组。
            var selection = entry.getValue();
            var options = perMetric.computeIfAbsent(entry.getKey(), this::singleMetricOptions);
            var option = options.get(selection.optionId());
            if (option == null) throw new SecurityException(DENIED);
            if (!(option.get(DIMENSION_TYPE) instanceof String type))
                throw new IllegalStateException("绑定维度缺少类型");
            var grain = selection.grain();
            if (grain == null
                    ? TIME_TYPE.equals(type)
                    : !(option.get(GRANULARITIES) instanceof List<?> grains
                            && grains.contains(grain)))
                throw new IllegalArgumentException("绑定维度的时间粒度无效");
            if (alignedType != null
                    && (!alignedType.equals(type) || !Objects.equals(alignedGrain, grain)))
                throw new IllegalArgumentException("对齐的绑定维度必须具有相同类型和粒度");
            alignedType = type;
            alignedGrain = grain;
        }
    }

    private Map<String, Map<String, Object>> singleMetricOptions(String metric) {
        var view = dimensions(new AvailableDimensionsRequest(List.of(metric)));
        return view.dimensions().stream()
                .collect(
                        Collectors.toMap(
                                option -> (String) option.get(OPTION_ID), option -> option));
    }
}
