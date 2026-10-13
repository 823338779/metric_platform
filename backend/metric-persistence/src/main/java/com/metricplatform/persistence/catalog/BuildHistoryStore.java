package com.metricplatform.persistence.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildHistoryRepository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 同事务更新事实投影及水位，永远不成为部署执行权威。 */
@Repository
public class BuildHistoryStore implements BuildHistoryRepository {
    private static final String BUILD = "BUILD",
            TARGET = "DEPLOYMENT_TARGET",
            ATTEMPT = "DEPLOYMENT_ATTEMPT",
            BRANCH = "branchName";

    /** 负责 MyBatis 行锁及版本条件更新的持久化服务。 */
    private final com.metricplatform.persistence.service.BuildHistoryPersistenceService persistence;

    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public BuildHistoryStore(
            com.metricplatform.persistence.service.BuildHistoryPersistenceService persistence) {
        this.persistence = persistence;
    }

    public String cursor() {
        return persistence.cursor().getCursor();
    }

    @Transactional
    public void apply(ChangePage page, String expectedCursor) {
        String prior = persistence.lock();
        if (!Objects.equals(prior, expectedCursor))
            throw new IllegalStateException("历史水位已被其他同步器推进");
        if (page.items().isEmpty()) return;
        long committed = number(prior), last = committed;
        // 先验证有序事实；任何异常触发整个事务回滚，不遗留部分投影。
        for (var event : page.items()) {
            if (event.sequence() <= committed) continue;
            if (event.sequence() <= last || event.objectVersion() < 1)
                throw new IllegalArgumentException("变化页序号或版本无效");
            if (BUILD.equals(event.objectType())) {
                var view = buildView(json(event.summary()));
                if (view.buildId() == null
                        || !view.buildId().toString().equals(event.objectId())
                        || !Objects.equals(view.repository(), event.repository())
                        || view.buildStatus() == null
                        || view.execution() == null)
                    throw new IllegalArgumentException("构建摘要身份或必需字段无效");
                var row = new com.metricplatform.persistence.entity.BuildHistoryEntity();
                row.setBuildId(UUID.fromString(event.objectId()));
                row.setRepository(event.repository());
                row.setBranchName((String) event.summary().get(BRANCH));
                row.setObjectVersion(event.objectVersion());
                row.setFirstSequence(event.sequence());
                row.setLastSequence(event.sequence());
                row.setSummary(json(event.summary()));
                persistence.apply(row);
            } else if (TARGET.equals(event.objectType()) || ATTEMPT.equals(event.objectType())) {
                var row = new com.metricplatform.persistence.entity.DeploymentHistoryEntity();
                row.setObjectType(event.objectType());
                row.setObjectId(event.objectId());
                row.setRepository(event.repository());
                row.setObjectVersion(event.objectVersion());
                row.setLastSequence(event.sequence());
                row.setSummary(json(event.summary()));
                persistence.apply(row);
            } else throw new IllegalArgumentException("变化页对象类型未知");
            last = event.sequence();
        }
        if (number(page.nextCursor()) != last) throw new IllegalArgumentException("变化页游标与事实序号不一致");
        if (persistence.advance(page.nextCursor(), expectedCursor) != 1)
            throw new IllegalStateException("历史水位提交冲突");
    }

    public BuildPage list(BuildFilter filter) {
        if (filter.limit() < 1 || filter.limit() > 200)
            throw new IllegalArgumentException("历史分页大小无效");
        var rows =
                persistence.history(
                        filter.repository(),
                        filter.branchName(),
                        pageOffset(filter),
                        filter.limit() + 1);
        boolean more = rows.size() > filter.limit();
        var selected = rows.subList(0, Math.min(rows.size(), filter.limit()));
        var items = selected.stream().map(s -> buildView(s.getSummary())).toList();
        String next = null;
        if (more && !items.isEmpty())
            next = pageCursor(filter, selected.getLast().getFirstSequence());
        return new BuildPage(
                items,
                next,
                "SERVICE_CHANGE_FEED",
                persistence.cursor().getSyncedAt() == null
                        ? null
                        : persistence.cursor().getSyncedAt().toString());
    }

    /** 本地分页游标绑定可选仓库、分支及首次变化序号；省略的筛选条件也是作用域。 */
    private record PageCursor(String repository, String branchName, long firstSequence) {}

    private long pageOffset(BuildFilter filter) {
        if (filter.cursor() == null) return 0;
        try {
            var cursor =
                    mapper.readValue(
                            Base64.getUrlDecoder().decode(filter.cursor()), PageCursor.class);
            if (!Objects.equals(cursor.repository(), filter.repository())
                    || !Objects.equals(cursor.branchName(), filter.branchName())
                    || cursor.firstSequence() < 0)
                throw new IllegalArgumentException("历史游标不属于当前仓库和分支");
            return cursor.firstSequence();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("历史游标无效", e);
        }
    }

    private String pageCursor(BuildFilter filter, long sequence) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        json(new PageCursor(filter.repository(), filter.branchName(), sequence))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 变化摘要以 errorCode 封存错误；只做视图映射，不改写执行状态。 */
    private BuildView buildView(String summary) {
        try {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(summary);
            var code = tree.get("errorCode");
            if (code != null && !code.isNull())
                tree.set(
                        "error",
                        mapper.valueToTree(
                                new ErrorView(
                                        code.asText(),
                                        "引擎构建未完成",
                                        false,
                                        tree.path("phase").asText(null),
                                        UUID.fromString(tree.path("buildId").asText()))));
            return mapper.treeToValue(tree, BuildView.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("历史摘要协议无效", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("历史摘要无效", e);
        }
    }

    private static long number(String cursor) {
        return cursor == null ? 0 : Long.parseLong(cursor);
    }
}
