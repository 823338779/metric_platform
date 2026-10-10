CREATE TABLE build_history (
 build_id uuid PRIMARY KEY, repository text NOT NULL, branch_name text,
 object_version bigint NOT NULL, first_sequence bigint NOT NULL, last_sequence bigint NOT NULL,
 summary jsonb NOT NULL, schema_version integer NOT NULL DEFAULT 3,
 synced_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX build_history_repository_branch ON build_history(repository,branch_name,first_sequence);
CREATE UNIQUE INDEX build_history_first_sequence ON build_history(first_sequence);
CREATE TABLE deployment_history (
 object_type text NOT NULL, object_id text NOT NULL, repository text NOT NULL,
 object_version bigint NOT NULL, last_sequence bigint NOT NULL, summary jsonb NOT NULL,
 synced_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(object_type,object_id)
);
CREATE TABLE history_cursor (consumer text PRIMARY KEY, cursor text, synced_at timestamptz);
INSERT INTO history_cursor(consumer) VALUES ('build-history');
-- 稳定映射只由已核验的服务迁移报告显式导入；无法映射的旧记录保持原样。
CREATE TABLE legacy_build_mapping (
 release_id uuid PRIMARY KEY REFERENCES semantic_release(release_id),
 build_id uuid NOT NULL, repository text NOT NULL, source_evidence text NOT NULL
);
ALTER TABLE semantic_query ADD COLUMN build_id uuid;
ALTER TABLE semantic_query ADD COLUMN user_id uuid;
ALTER TABLE semantic_query ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE semantic_query ALTER COLUMN release_id DROP NOT NULL;
CREATE TABLE build_query_audit (
 query_id uuid PRIMARY KEY, build_id uuid NOT NULL, user_id uuid NOT NULL,
 idempotency_key text NOT NULL, submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_branch_cache (
 agent_project_id text PRIMARY KEY, response_json jsonb NOT NULL, observed_at timestamptz NOT NULL
);
COMMENT ON COLUMN build_history.build_id IS '服务稳定构建身份';
COMMENT ON COLUMN build_history.repository IS '规范仓库地址';
COMMENT ON COLUMN build_history.branch_name IS '来源分支短名';
COMMENT ON COLUMN build_history.object_version IS '服务对象递增版本';
COMMENT ON COLUMN build_history.first_sequence IS '首次观察的变化序号，作为不可变历史分页键';
COMMENT ON COLUMN build_history.last_sequence IS '最后应用的变化序号';
COMMENT ON COLUMN build_history.summary IS '服务有界事实摘要，不是可编辑目录';
COMMENT ON COLUMN build_history.schema_version IS '摘要协议版本';
COMMENT ON COLUMN build_history.synced_at IS '平台同步观察时间';
COMMENT ON COLUMN deployment_history.object_type IS '目标或部署尝试对象类型';
COMMENT ON COLUMN deployment_history.object_id IS '服务对象稳定身份';
COMMENT ON COLUMN deployment_history.repository IS '规范仓库地址';
COMMENT ON COLUMN deployment_history.object_version IS '服务对象版本';
COMMENT ON COLUMN deployment_history.last_sequence IS '最后应用序号';
COMMENT ON COLUMN deployment_history.summary IS '只读部署摘要，不参与部署决策';
COMMENT ON COLUMN deployment_history.synced_at IS '平台同步时间';
COMMENT ON COLUMN history_cursor.consumer IS '消费者唯一标识';
COMMENT ON COLUMN history_cursor.cursor IS '已原子提交的服务水位';
COMMENT ON COLUMN history_cursor.synced_at IS '水位成功提交时间';
COMMENT ON COLUMN legacy_build_mapping.release_id IS '旧平台版本身份';
COMMENT ON COLUMN legacy_build_mapping.build_id IS '报告确认的服务构建身份';
COMMENT ON COLUMN legacy_build_mapping.repository IS '可信来源仓库';
COMMENT ON COLUMN legacy_build_mapping.source_evidence IS '只读迁移报告来源与摘要';
COMMENT ON COLUMN semantic_query.build_id IS '新服务构建身份，旧关联保留';
COMMENT ON COLUMN semantic_query.user_id IS '查询发起的平台身份';
COMMENT ON COLUMN build_query_audit.query_id IS '服务受理查询身份';
COMMENT ON COLUMN build_query_audit.build_id IS '本次查询固定构建';
COMMENT ON COLUMN build_query_audit.user_id IS '已认证平台身份';
COMMENT ON COLUMN build_query_audit.idempotency_key IS '查询原始幂等键';
COMMENT ON COLUMN build_query_audit.submitted_at IS '平台归因时间';
COMMENT ON COLUMN agent_branch_cache.agent_project_id IS 'Agent 项目身份';
COMMENT ON COLUMN agent_branch_cache.response_json IS '只读分支观察快照';
COMMENT ON COLUMN agent_branch_cache.observed_at IS '真实读取 Agent 的时间';

-- 仅接受经人工核对的只读服务映射报告；重复导入稳定，冲突不改变原关联。
CREATE FUNCTION import_legacy_build_mapping(old_release uuid, new_build uuid, source_repository text, evidence text)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE prior legacy_build_mapping%ROWTYPE;
BEGIN
 IF old_release IS NULL OR new_build IS NULL OR trim(source_repository)='' OR trim(evidence)='' OR source_repository IS NULL OR evidence IS NULL THEN
  RAISE EXCEPTION 'verified migration mapping and source evidence are required';
 END IF;
 PERFORM 1 FROM semantic_release WHERE release_id=old_release FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'legacy release does not exist'; END IF;
 SELECT * INTO prior FROM legacy_build_mapping WHERE release_id=old_release;
 IF FOUND AND (prior.build_id<>new_build OR prior.repository<>source_repository OR prior.source_evidence<>evidence) THEN
  RAISE EXCEPTION 'legacy build mapping conflicts with existing evidence';
 END IF;
 INSERT INTO legacy_build_mapping VALUES(old_release,new_build,source_repository,evidence) ON CONFLICT DO NOTHING;
 UPDATE semantic_query SET build_id=new_build WHERE release_id=old_release AND build_id IS NULL;
 IF EXISTS(SELECT 1 FROM semantic_query WHERE release_id=old_release AND build_id<>new_build) THEN
  RAISE EXCEPTION 'historical query already references a different build';
 END IF;
 RETURN true;
END $$;
