package com.metricplatform.dbt;

import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 在同一 release 行锁下预留查询引用，防止与后台 schema 清理竞态。 */
@Repository
@RequiredArgsConstructor
public class DbtQueryStore {
    private static final String LOCK_RELEASE =
            "select state, cleanup_state from semantic_release where release_id=? and project_id=? for update";
    private static final String INSERT =
            "insert into semantic_query(query_id,project_id,release_id,idempotency_key,mode,status) values (?,?,?,?,?,?)";
    private static final String COUNT_NON_TERMINAL =
            "select count(*) from semantic_query where release_id=? and status in ('PENDING','RUNNING')";
    private static final String COMPLETE =
            "update semantic_query set status='COMPLETED',completed_at=current_timestamp where query_id=?";
    private static final String FIND =
            "select query_id,release_id,idempotency_key,runtime_query_id,mode,status,error_code from semantic_query where project_id=? and query_id=?";
    private static final String PENDING_QUERIES =
            "select project_id,query_id from semantic_query where status in ('PENDING','RUNNING') order by submitted_at limit ?";
    private static final String ATTACH =
            "update semantic_query set runtime_query_id=?,status='RUNNING' where query_id=? and status in ('PENDING','RUNNING')";
    private static final String UPDATE =
            "update semantic_query set runtime_query_id=coalesce(runtime_query_id,?),status=?,error_code=?,completed_at=case when ? in ('COMPLETED','FAILED','CANCELLED') then current_timestamp else null end where query_id=?";
    private static final String PUBLISHED = "PUBLISHED", PENDING = "PENDING", QUERY = "QUERY";
    private final JdbcTemplate jdbc;

    /** 查询元数据与结果的物理版本引用。 */
    public record QueryRecord(
            UUID queryId,
            UUID releaseId,
            UUID idempotencyKey,
            UUID runtimeQueryId,
            String mode,
            String status,
            String errorCode) {}

    /** 后台恢复需要的最小任务引用，不包含用户查询参数和结果。 */
    public record PendingQuery(String projectId, UUID queryId) {}

    public java.util.List<PendingQuery> pending(int limit) {
        return jdbc.query(
                PENDING_QUERIES,
                (rs, row) -> new PendingQuery(rs.getString(1), rs.getObject(2, UUID.class)),
                limit);
    }

    @Transactional
    public void reserve(UUID queryId, String projectId, UUID releaseId, UUID idempotencyKey) {
        reserve(queryId, projectId, releaseId, idempotencyKey, QUERY);
    }

    @Transactional
    public void reserve(
            UUID queryId, String projectId, UUID releaseId, UUID idempotencyKey, String mode) {
        var release = jdbc.queryForMap(LOCK_RELEASE, releaseId, projectId);
        if (!PUBLISHED.equals(release.get("state"))) throw new IllegalStateException("发布版本不可查询");
        if (!PENDING.equals(release.get("cleanup_state")))
            throw new ResponseStatusException(HttpStatus.GONE, "发布版本已清理");
        jdbc.update(INSERT, queryId, projectId, releaseId, idempotencyKey, mode, PENDING);
    }

    public QueryRecord find(String projectId, UUID queryId) {
        return jdbc.query(
                FIND,
                rs ->
                        rs.next()
                                ? new QueryRecord(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getObject(3, UUID.class),
                                        rs.getObject(4, UUID.class),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7))
                                : missing(),
                projectId,
                queryId);
    }

    @Transactional
    public void attach(UUID queryId, UUID runtimeQueryId) {
        jdbc.update(ATTACH, runtimeQueryId, queryId);
    }

    @Transactional
    public void update(UUID queryId, UUID runtimeQueryId, String status, String errorCode) {
        jdbc.update(UPDATE, runtimeQueryId, status, errorCode, status, queryId);
    }

    private static QueryRecord missing() {
        throw new NoSuchElementException("查询任务不存在");
    }

    public int countNonTerminal(UUID releaseId) {
        return jdbc.queryForObject(COUNT_NON_TERMINAL, Integer.class, releaseId);
    }

    @Transactional
    public void complete(UUID queryId) {
        jdbc.update(COMPLETE, queryId);
    }
}
