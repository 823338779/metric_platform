ALTER TABLE metric ADD COLUMN fixed_filter_expression TEXT;
COMMENT ON COLUMN metric.fixed_filter_expression IS '指标级固定过滤 FilterExpression JSON；空值表示没有固定过滤';
ALTER TABLE dataset_relationship ADD COLUMN join_type VARCHAR(8) NOT NULL DEFAULT 'LEFT' CHECK (join_type IN ('LEFT', 'INNER'));
COMMENT ON COLUMN dataset_relationship.join_type IS '从源数据集到目标数据集的连接语义，旧人工定义默认 LEFT';

CREATE TABLE git_sync_state (
    repository_id VARCHAR(160) PRIMARY KEY,
    model_name VARCHAR(160) NOT NULL,
    data_source_id UUID NOT NULL,
    schema_name VARCHAR(128) NOT NULL,
    applied_commit VARCHAR(64),
    definition_json TEXT,
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE git_sync_state IS '每个 Git 仓库的原子发布检查点与最近错误';
COMMENT ON COLUMN git_sync_state.repository_id IS '部署配置中的稳定仓库标识及行锁作用域';
COMMENT ON COLUMN git_sync_state.model_name IS '固定模型名，禁止同一仓库换名覆盖';
COMMENT ON COLUMN git_sync_state.data_source_id IS '首次消费时绑定的数据源 UUID；后续配置不得改变对象数据来源';
COMMENT ON COLUMN git_sync_state.schema_name IS '首次消费时绑定的物理 schema；与数据源及模型共同形成不可变绑定';
COMMENT ON COLUMN git_sync_state.applied_commit IS '与业务定义同一事务提交的 SHA；未成功时为空';
COMMENT ON COLUMN git_sync_state.definition_json IS '最近成功的完整归一化定义，保留未映射的平台扩展信息';
COMMENT ON COLUMN git_sync_state.error_code IS '最近一次当前检查点上的消费错误码；成功后清空';
COMMENT ON COLUMN git_sync_state.error_message IS '不含凭据的消费错误摘要；成功后清空';
COMMENT ON COLUMN git_sync_state.updated_at IS '检查点或错误状态最近更新时间';

CREATE TABLE git_object_ownership (
    object_id UUID PRIMARY KEY,
    repository_id VARCHAR(160) NOT NULL REFERENCES git_sync_state(repository_id),
    model_name VARCHAR(160) NOT NULL,
    kind VARCHAR(40) NOT NULL CHECK (kind IN ('dataset', 'dataset_field', 'dimension', 'metric', 'dataset_relationship')),
    logical_name VARCHAR(400) NOT NULL,
    UNIQUE(repository_id, model_name, kind, logical_name)
);
COMMENT ON TABLE git_object_ownership IS 'Git 逻辑对象到平台 UUID 的永久归属；业务软删除后仍保留以便恢复';
COMMENT ON COLUMN git_object_ownership.object_id IS '平台对象稳定 UUID，软删除和恢复不改变';
COMMENT ON COLUMN git_object_ownership.repository_id IS '唯一拥有该对象的 Git 仓库';
COMMENT ON COLUMN git_object_ownership.model_name IS '对象所属固定模型';
COMMENT ON COLUMN git_object_ownership.kind IS '受管理的平台对象表类型';
COMMENT ON COLUMN git_object_ownership.logical_name IS '模型内逻辑名；字段和维度使用数据集名加字段名';

-- 软删除对象保留身份，但不占用活动对象的名称或物理列，支持快照中的逻辑重命名。
ALTER TABLE dataset DROP CONSTRAINT dataset_data_source_id_name_key;
CREATE UNIQUE INDEX dataset_active_name ON dataset(data_source_id, name) WHERE NOT is_deleted;
ALTER TABLE dataset_field DROP CONSTRAINT dataset_field_dataset_id_physical_name_key;
CREATE UNIQUE INDEX dataset_field_active_column ON dataset_field(dataset_id, physical_name) WHERE NOT is_deleted;
ALTER TABLE dimension DROP CONSTRAINT dimension_dataset_id_name_key;
ALTER TABLE dimension DROP CONSTRAINT dimension_dataset_id_dataset_field_id_key;
CREATE UNIQUE INDEX dimension_active_name ON dimension(dataset_id, name) WHERE NOT is_deleted;
CREATE UNIQUE INDEX dimension_active_field ON dimension(dataset_id, dataset_field_id) WHERE NOT is_deleted;
ALTER TABLE metric DROP CONSTRAINT metric_dataset_id_name_key;
CREATE UNIQUE INDEX metric_active_name ON metric(dataset_id, name) WHERE NOT is_deleted;
ALTER TABLE dataset_relationship DROP CONSTRAINT dataset_relationship_source_dataset_id_name_key;
CREATE UNIQUE INDEX relationship_active_name ON dataset_relationship(source_dataset_id, name) WHERE NOT is_deleted;

-- 事务本地仓库标识仅由发布服务设置，连接归还池后自动失效。数据库守卫覆盖管理接口、
-- schema drift 和关联子表写入；直接依赖 Git 对象的人工定义同样拒绝，避免删除竞态。
CREATE FUNCTION guard_git_semantic_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_row JSONB;
    new_row JSONB;
    protected_ids UUID[];
    owner_repo TEXT;
    publishing_repo TEXT := current_setting('metric_platform.git_repository', true);
BEGIN
    old_row := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
    new_row := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) ELSE '{}'::jsonb END;
    protected_ids := ARRAY[
        (old_row->>'id')::uuid, (new_row->>'id')::uuid,
        (old_row->>'dataset_id')::uuid, (new_row->>'dataset_id')::uuid,
        (old_row->>'dataset_field_id')::uuid, (new_row->>'dataset_field_id')::uuid,
        (old_row->>'source_dataset_id')::uuid, (new_row->>'source_dataset_id')::uuid,
        (old_row->>'target_dataset_id')::uuid, (new_row->>'target_dataset_id')::uuid,
        (old_row->>'source_field_id')::uuid, (new_row->>'source_field_id')::uuid,
        (old_row->>'target_field_id')::uuid, (new_row->>'target_field_id')::uuid,
        (old_row->>'relationship_id')::uuid, (new_row->>'relationship_id')::uuid,
        (old_row->>'dimension_id')::uuid, (new_row->>'dimension_id')::uuid,
        (old_row->>'default_time_dimension_id')::uuid, (new_row->>'default_time_dimension_id')::uuid
    ];
    SELECT repository_id INTO owner_repo FROM git_object_ownership
    WHERE object_id = ANY(protected_ids) AND repository_id IS DISTINCT FROM publishing_repo LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Git-managed definitions must be changed in their repository' USING ERRCODE = 'GIT01';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER git_guard_dataset BEFORE INSERT OR UPDATE OR DELETE ON dataset FOR EACH ROW EXECUTE FUNCTION guard_git_semantic_write();
CREATE TRIGGER git_guard_field BEFORE INSERT OR UPDATE OR DELETE ON dataset_field FOR EACH ROW EXECUTE FUNCTION guard_git_semantic_write();
CREATE TRIGGER git_guard_dimension BEFORE INSERT OR UPDATE OR DELETE ON dimension FOR EACH ROW EXECUTE FUNCTION guard_git_semantic_write();
CREATE TRIGGER git_guard_metric BEFORE INSERT OR UPDATE OR DELETE ON metric FOR EACH ROW EXECUTE FUNCTION guard_git_semantic_write();
CREATE TRIGGER git_guard_relationship BEFORE INSERT OR UPDATE OR DELETE ON dataset_relationship FOR EACH ROW EXECUTE FUNCTION guard_git_semantic_write();
CREATE TRIGGER git_guard_pair BEFORE INSERT OR UPDATE OR DELETE ON dataset_relationship_field_pair FOR EACH ROW EXECUTE FUNCTION guard_git_semantic_write();
CREATE TRIGGER git_guard_granularity BEFORE INSERT OR UPDATE OR DELETE ON dimension_time_granularity FOR EACH ROW EXECUTE FUNCTION guard_git_semantic_write();
