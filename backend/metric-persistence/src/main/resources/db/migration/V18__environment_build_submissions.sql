CREATE TABLE environment_build_submission (
 actor_id uuid NOT NULL,
 idempotency_key varchar(256) NOT NULL,
 environment_id uuid NOT NULL REFERENCES execution_environment,
 command jsonb NOT NULL,
 request jsonb NOT NULL,
 build_id uuid,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_id,idempotency_key)
);
COMMENT ON TABLE environment_build_submission IS '构建提交固定请求及幂等恢复记录，不镜像服务构建状态';
COMMENT ON COLUMN environment_build_submission.actor_id IS '提交登录身份';
COMMENT ON COLUMN environment_build_submission.idempotency_key IS '用户一次操作的稳定请求键';
COMMENT ON COLUMN environment_build_submission.environment_id IS '选定的执行环境';
COMMENT ON COLUMN environment_build_submission.command IS '用于重复输入一致性校验的原始操作';
COMMENT ON COLUMN environment_build_submission.request IS '包含固定执行快照的服务请求，无明文凭据';
COMMENT ON COLUMN environment_build_submission.build_id IS '服务确认的构建身份，未知时为空';
COMMENT ON COLUMN environment_build_submission.created_at IS '首次提交时间';
