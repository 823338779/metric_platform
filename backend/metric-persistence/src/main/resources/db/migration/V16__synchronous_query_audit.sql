-- 同步查询不再拥有服务查询 ID；独立保存平台本地执行归因，旧审计保持原义。
CREATE TABLE query_execution_audit (
 execution_id uuid PRIMARY KEY,
 build_id uuid NOT NULL,
 user_id uuid NOT NULL,
 completed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE query_execution_audit IS '同步查询成功完成后的平台本地执行归因';
COMMENT ON COLUMN query_execution_audit.execution_id IS '平台本地执行关联 ID，不是 dbt-service 查询身份';
COMMENT ON COLUMN query_execution_audit.build_id IS '执行固定的服务构建身份';
COMMENT ON COLUMN query_execution_audit.user_id IS '发起执行的已认证平台用户';
COMMENT ON COLUMN query_execution_audit.completed_at IS '平台记录成功执行的时间';
