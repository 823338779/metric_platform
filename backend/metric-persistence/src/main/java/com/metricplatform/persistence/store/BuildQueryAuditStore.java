package com.metricplatform.persistence.store;

import com.metricplatform.query.QueryAuditRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 同步查询完成后保存本地执行归因，不生成服务查询身份。 */
@Repository
public class BuildQueryAuditStore implements QueryAuditRepository {
    private static final String INSERT =
            "insert into query_execution_audit(execution_id,build_id,user_id)"
                + " values(?,?,?)";
    /** 平台数据库的参数化 SQL 访问。 */
    private final JdbcTemplate jdbc;

    public BuildQueryAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID execution, UUID build, UUID user) {
        jdbc.update(INSERT, execution, build, user);
    }
}
