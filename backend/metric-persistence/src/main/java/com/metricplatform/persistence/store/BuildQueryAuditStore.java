package com.metricplatform.persistence.store;

import com.metricplatform.query.QueryAuditRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 服务查询受理后保存身份归因，重试不会改写原用户。 */
@Repository
public class BuildQueryAuditStore implements QueryAuditRepository {
    private static final String INSERT =
            "insert into build_query_audit(query_id,build_id,user_id,idempotency_key)"
                + " values(?,?,?,?) on conflict(query_id) do nothing";
    /** 平台数据库的参数化 SQL 访问。 */
    private final JdbcTemplate jdbc;

    public BuildQueryAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID query, UUID build, UUID user, String key) {
        jdbc.update(INSERT, query, build, user, key);
    }
}
