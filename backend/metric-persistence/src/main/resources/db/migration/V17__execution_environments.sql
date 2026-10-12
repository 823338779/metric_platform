CREATE TABLE execution_environment (
 environment_id uuid PRIMARY KEY,
 name varchar(128) NOT NULL,
 config_version bigint NOT NULL CHECK(config_version > 0),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE execution_environment_version (
 environment_id uuid NOT NULL REFERENCES execution_environment,
 config_version bigint NOT NULL,
 execution jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(environment_id, config_version)
);
CREATE TABLE execution_namespace_owner (
 instance_id text NOT NULL,
 adapter_type text NOT NULL,
 catalog_name text NOT NULL,
 schema_name text NOT NULL,
 environment_id uuid NOT NULL REFERENCES execution_environment,
 PRIMARY KEY(instance_id, adapter_type, catalog_name, schema_name)
);
COMMENT ON TABLE execution_environment IS '平台执行环境，不绑定仓库和分支';
COMMENT ON COLUMN execution_environment.environment_id IS '稳定环境身份';
COMMENT ON COLUMN execution_environment.name IS '展示名称';
COMMENT ON COLUMN execution_environment.config_version IS '当前不可变配置版本';
COMMENT ON COLUMN execution_environment.created_at IS '创建时间';
COMMENT ON COLUMN execution_environment.updated_at IS '最近修改时间';
COMMENT ON TABLE execution_environment_version IS '不可变环境配置版本，不保存明文凭据';
COMMENT ON COLUMN execution_environment_version.environment_id IS '所属环境';
COMMENT ON COLUMN execution_environment_version.config_version IS '配置版本';
COMMENT ON COLUMN execution_environment_version.execution IS '完整非敏感执行快照及凭据引用';
COMMENT ON COLUMN execution_environment_version.created_at IS '该版本创建时间';
COMMENT ON TABLE execution_namespace_owner IS '物理命名空间归属，配置变更后仍保留历史目标';
COMMENT ON COLUMN execution_namespace_owner.instance_id IS '规范化物理实例身份';
COMMENT ON COLUMN execution_namespace_owner.adapter_type IS '数据库适配器';
COMMENT ON COLUMN execution_namespace_owner.catalog_name IS '实际目标 catalog';
COMMENT ON COLUMN execution_namespace_owner.schema_name IS '实际目标 schema';
COMMENT ON COLUMN execution_namespace_owner.environment_id IS '唯一所属环境';
