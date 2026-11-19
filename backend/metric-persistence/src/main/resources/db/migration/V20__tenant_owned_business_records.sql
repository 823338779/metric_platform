-- 允许在多租户旧库中预先补录经核实的归属。迁移结束时所有表使用同样的非空外键，无默认租户。
CREATE TEMP TABLE tenant_backfill_tables(name text PRIMARY KEY, meaning text NOT NULL) ON COMMIT DROP;
INSERT INTO tenant_backfill_tables VALUES
    ('platform_access_token', '签发该令牌的账号所属租户'),
    ('audit_event', '产生该审计事件的租户'),
    ('execution_environment_version', '保存该配置版本的环境所属租户'),
    ('execution_namespace_owner', '占用该物理命名空间的环境所属租户'),
    ('environment_build_submission', '发起该构建提交的租户'),
    ('query_execution_audit', '完成本次查询执行的租户'),
    ('build_query_audit', '提交本次服务查询的租户'),
    ('semantic_project', '持有该历史语义项目的租户'),
    ('semantic_release', '持有该项目发布版本的租户'),
    ('semantic_resource', '持有该发布资源的租户'),
    ('semantic_dependency', '持有该版本资源依赖的租户'),
    ('semantic_query', '提交本次语义查询的租户'),
    ('legacy_build_mapping', '持有该历史版本与构建映射的租户'),
    ('build_history', '持有该构建历史的租户'),
    ('deployment_history', '持有该部署历史的租户'),
    ('history_cursor', '独立消费该历史水位的租户'),
    ('agent_branch_cache', '持有该历史项目分支快照的租户');
INSERT INTO tenant_backfill_tables SELECT 'tenant_cutover_submission_archive', '持有该归档构建提交的租户'
WHERE to_regclass('tenant_cutover_submission_archive') IS NOT NULL;

DO $$
DECLARE t record;
BEGIN
    FOR t IN SELECT * FROM tenant_backfill_tables ORDER BY name LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS tenant_id varchar(8)', t.name);
        EXECUTE format('COMMENT ON COLUMN %I.tenant_id IS %L', t.name, t.meaning);
    END LOOP;
END $$;

-- 从 V19 已认证的账号、环境与构建逐级回填；下方的复合外键会拒绝互相矛盾的证据。
UPDATE platform_access_token t SET tenant_id=u.tenant_id FROM platform_user u WHERE u.id=t.identity_id;
UPDATE execution_environment_version v SET tenant_id=e.tenant_id FROM execution_environment e WHERE e.environment_id=v.environment_id;
UPDATE execution_namespace_owner n SET tenant_id=e.tenant_id FROM execution_environment e WHERE e.environment_id=n.environment_id;
UPDATE environment_build_submission s SET tenant_id=e.tenant_id FROM execution_environment e WHERE e.environment_id=s.environment_id;
DO $$ BEGIN
    IF EXISTS (SELECT FROM build_ownership b JOIN execution_environment e USING(environment_id)
               WHERE b.tenant_id<>e.tenant_id)
       OR EXISTS (SELECT FROM build_ownership GROUP BY environment_id HAVING count(DISTINCT tenant_id)>1) THEN
        RAISE EXCEPTION 'build_ownership conflicts with the owner of its environment';
    END IF;
    IF to_regclass('tenant_cutover_submission_archive') IS NOT NULL THEN
        UPDATE tenant_cutover_submission_archive a SET tenant_id=e.tenant_id
            FROM execution_environment e WHERE e.environment_id=a.environment_id;
    END IF;
END $$;
UPDATE query_execution_audit q SET tenant_id=b.tenant_id FROM build_ownership b WHERE b.build_id=q.build_id;
UPDATE build_query_audit q SET tenant_id=b.tenant_id FROM build_ownership b WHERE b.build_id=q.build_id;
UPDATE build_history h SET tenant_id=b.tenant_id FROM build_ownership b WHERE b.build_id=h.build_id;
UPDATE legacy_build_mapping m SET tenant_id=b.tenant_id FROM build_ownership b WHERE b.build_id=m.build_id;
UPDATE semantic_query q SET tenant_id=b.tenant_id FROM build_ownership b WHERE b.build_id=q.build_id;
UPDATE semantic_query q SET tenant_id=u.tenant_id FROM platform_user u WHERE u.id=q.user_id AND q.tenant_id IS NULL;

-- 同一旧项目有多条查询和发布记录，必须对租户达成一致。版本暂未映射时也能由已关联查询确定项目。
WITH evidence AS (
    SELECT r.project_id, m.tenant_id FROM semantic_release r JOIN legacy_build_mapping m USING(release_id)
    UNION
    SELECT q.project_id, q.tenant_id FROM semantic_query q
    UNION
    SELECT r.project_id, q.tenant_id FROM semantic_query q JOIN semantic_release r USING(release_id)
)
UPDATE semantic_project p SET tenant_id=(
    SELECT DISTINCT tenant_id FROM evidence WHERE project_id=p.project_id AND tenant_id IS NOT NULL
) WHERE EXISTS (SELECT FROM evidence WHERE project_id=p.project_id AND tenant_id IS NOT NULL);

-- 全局空水位只是 V15 安装时的占位行。每个租户首次消费时创建自己的水位。
DELETE FROM history_cursor WHERE consumer='build-history' AND cursor IS NULL AND synced_at IS NULL AND tenant_id IS NULL;

-- V19 之前的数据属于迁入的首租户；多租户上线后的无主数据必须有证据，禁止猜测。
CREATE TEMP VIEW historical_first_tenant AS
    SELECT t.tenant_id, (SELECT installed_on FROM flyway_schema_history WHERE version='19' AND success) AS cutover_at,
           NOT EXISTS (SELECT FROM platform_tenant WHERE tenant_id<>t.tenant_id) AS only_tenant
    FROM platform_tenant t WHERE t.tenant_id='tn_00001';
UPDATE semantic_project p SET tenant_id=t.tenant_id FROM historical_first_tenant t
    WHERE p.tenant_id IS NULL AND (t.only_tenant OR p.last_observed_at < t.cutover_at
        OR EXISTS (SELECT FROM semantic_release r WHERE r.project_id=p.project_id AND r.created_at<t.cutover_at));
UPDATE semantic_release r SET tenant_id=p.tenant_id FROM semantic_project p WHERE p.project_id=r.project_id;
UPDATE semantic_resource s SET tenant_id=r.tenant_id FROM semantic_release r WHERE r.release_id=s.release_id;
UPDATE semantic_dependency d SET tenant_id=r.tenant_id FROM semantic_release r WHERE r.release_id=d.release_id;
UPDATE semantic_query q SET tenant_id=r.tenant_id FROM semantic_release r WHERE r.release_id=q.release_id AND q.tenant_id IS NULL;
UPDATE semantic_query q SET tenant_id=p.tenant_id FROM semantic_project p WHERE p.project_id=q.project_id AND q.tenant_id IS NULL;
UPDATE semantic_query q SET tenant_id=t.tenant_id FROM historical_first_tenant t
    WHERE q.tenant_id IS NULL AND (t.only_tenant OR q.submitted_at<t.cutover_at);
UPDATE agent_branch_cache a SET tenant_id=t.tenant_id FROM historical_first_tenant t
    WHERE a.tenant_id IS NULL AND (t.only_tenant OR a.observed_at<t.cutover_at);
UPDATE history_cursor c SET tenant_id=t.tenant_id FROM historical_first_tenant t
    WHERE c.tenant_id IS NULL AND (t.only_tenant OR c.synced_at<t.cutover_at);

-- 部署快照同时含意图构建、生效构建或环境时，它们必须指向同一归属。
UPDATE deployment_history d SET tenant_id=coalesce((
    SELECT DISTINCT owners.tenant_id FROM (
        SELECT b.tenant_id FROM build_ownership b WHERE b.build_id::text IN
            (d.summary->>'buildId', d.summary->>'activeBuildId', d.summary#>>'{latestAttempt,buildId}')
        UNION
        SELECT e.tenant_id FROM execution_environment e WHERE e.environment_id::text=d.summary->>'environmentId'
    ) owners
), d.tenant_id);
UPDATE deployment_history d SET tenant_id=t.tenant_id FROM historical_first_tenant t
    WHERE d.tenant_id IS NULL AND (t.only_tenant OR d.synced_at<t.cutover_at);

-- 字符串操作者兼容 bootstrap 等历史系统身份；UUID 账号操作者同时受到同租户外键约束。
ALTER TABLE audit_event ADD COLUMN actor_user_id uuid GENERATED ALWAYS AS (
    CASE WHEN actor_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
         THEN actor_id::uuid END) STORED;
COMMENT ON COLUMN audit_event.actor_user_id IS '操作者为平台账号时，用于校验其租户的 UUID';
UPDATE audit_event a SET tenant_id=u.tenant_id FROM platform_user u WHERE u.id=a.effective_identity_id;
UPDATE audit_event a SET tenant_id=u.tenant_id FROM platform_user u WHERE u.id=a.actor_user_id AND a.tenant_id IS NULL;
UPDATE audit_event a SET tenant_id=u.tenant_id FROM platform_user u
    WHERE a.resource_type='platform_user' AND u.id=a.resource_id AND a.tenant_id IS NULL;
UPDATE audit_event a SET tenant_id=t.tenant_id FROM historical_first_tenant t
    WHERE a.tenant_id IS NULL AND (t.only_tenant OR a.created_at<t.cutover_at);
-- 未识别账号的登录尝试只保存尝试目标；原始 UUID 仍完整保留在 resource_id 中。
UPDATE audit_event a SET effective_identity_id=NULL
    WHERE a.effective_identity_id=a.resource_id AND NOT EXISTS (SELECT FROM platform_user u WHERE u.id=a.effective_identity_id)
      AND (a.action ILIKE '%login%' OR a.resource_type ILIKE '%login%');
DROP VIEW historical_first_tenant;

DO $$
DECLARE t record; missing boolean;
BEGIN
    FOR t IN SELECT * FROM tenant_backfill_tables ORDER BY name LOOP
        EXECUTE format('SELECT EXISTS (SELECT FROM %I WHERE tenant_id IS NULL)', t.name) INTO missing;
        IF missing THEN
            RAISE EXCEPTION 'Unresolved tenant ownership in %. Verify affected rows, prefill tenant_id from operator evidence, then retry V20.', t.name;
        END IF;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN tenant_id DROP DEFAULT, ALTER COLUMN tenant_id SET NOT NULL, ADD FOREIGN KEY (tenant_id) REFERENCES platform_tenant(tenant_id)', t.name);
    END LOOP;
END $$;

-- 服务构建可来自历史外部环境；本地提交必须同时匹配提交人、环境和最终确认的构建。
ALTER TABLE execution_environment ADD UNIQUE(tenant_id,environment_id);
ALTER TABLE build_ownership ADD UNIQUE(tenant_id,build_id), ADD UNIQUE(tenant_id,environment_id,build_id);
ALTER TABLE execution_environment_version ADD FOREIGN KEY(tenant_id,environment_id) REFERENCES execution_environment(tenant_id,environment_id);
ALTER TABLE execution_namespace_owner ADD FOREIGN KEY(tenant_id,environment_id) REFERENCES execution_environment(tenant_id,environment_id);
ALTER TABLE environment_build_submission
    ADD FOREIGN KEY(tenant_id,actor_id) REFERENCES platform_user(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,environment_id) REFERENCES execution_environment(tenant_id,environment_id),
    ADD FOREIGN KEY(tenant_id,environment_id,build_id) REFERENCES build_ownership(tenant_id,environment_id,build_id) DEFERRABLE INITIALLY DEFERRED;
DO $$ BEGIN
    IF to_regclass('tenant_cutover_submission_archive') IS NOT NULL THEN
        ALTER TABLE tenant_cutover_submission_archive
            ADD FOREIGN KEY(tenant_id,actor_id) REFERENCES platform_user(tenant_id,id),
            ADD FOREIGN KEY(tenant_id,environment_id) REFERENCES execution_environment(tenant_id,environment_id),
            ADD FOREIGN KEY(tenant_id,environment_id,build_id) REFERENCES build_ownership(tenant_id,environment_id,build_id);
    END IF;
END $$;
ALTER TABLE platform_access_token ADD FOREIGN KEY(tenant_id,identity_id) REFERENCES platform_user(tenant_id,id) ON DELETE CASCADE;
ALTER TABLE audit_event
    ADD FOREIGN KEY(tenant_id,effective_identity_id) REFERENCES platform_user(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,actor_user_id) REFERENCES platform_user(tenant_id,id);
ALTER TABLE query_execution_audit
    ADD FOREIGN KEY(tenant_id,user_id) REFERENCES platform_user(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,build_id) REFERENCES build_ownership(tenant_id,build_id);
ALTER TABLE build_query_audit
    ADD FOREIGN KEY(tenant_id,user_id) REFERENCES platform_user(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,build_id) REFERENCES build_ownership(tenant_id,build_id);
ALTER TABLE build_history ADD UNIQUE(tenant_id,build_id),
    ADD FOREIGN KEY(tenant_id,build_id) REFERENCES build_ownership(tenant_id,build_id);
DROP INDEX build_history_first_sequence;
CREATE UNIQUE INDEX build_history_first_sequence ON build_history(tenant_id,first_sequence);
ALTER TABLE deployment_history DROP CONSTRAINT deployment_history_pkey, ADD PRIMARY KEY(tenant_id,object_type,object_id);
ALTER TABLE history_cursor DROP CONSTRAINT history_cursor_pkey, ADD PRIMARY KEY(tenant_id,consumer);
ALTER TABLE agent_branch_cache DROP CONSTRAINT agent_branch_cache_pkey, ADD PRIMARY KEY(tenant_id,agent_project_id);

-- 项目名、发布序号与查询幂等键是租户内标识；UUID 仍保持稳定，历史链接不变。
ALTER TABLE semantic_query DROP CONSTRAINT semantic_query_project_id_fkey;
ALTER TABLE semantic_release DROP CONSTRAINT semantic_release_project_id_fkey;
ALTER TABLE semantic_project DROP CONSTRAINT semantic_project_pkey, ADD PRIMARY KEY(tenant_id,project_id);
ALTER TABLE semantic_release DROP CONSTRAINT semantic_release_project_id_release_sequence_key,
    ADD UNIQUE(tenant_id,project_id,release_sequence), ADD UNIQUE(tenant_id,release_id),
    ADD FOREIGN KEY(tenant_id,project_id) REFERENCES semantic_project(tenant_id,project_id) ON DELETE CASCADE,
    ADD FOREIGN KEY(tenant_id,expected_active_release_id) REFERENCES semantic_release(tenant_id,release_id);
ALTER TABLE semantic_project ADD FOREIGN KEY(tenant_id,active_release_id) REFERENCES semantic_release(tenant_id,release_id);
ALTER TABLE semantic_resource ADD FOREIGN KEY(tenant_id,release_id) REFERENCES semantic_release(tenant_id,release_id) ON DELETE CASCADE;
-- 旧依赖边两端已有 (release_id,resource_id) 外键，同一版本只能属于一个租户。
ALTER TABLE semantic_dependency ADD FOREIGN KEY(tenant_id,release_id) REFERENCES semantic_release(tenant_id,release_id) ON DELETE CASCADE;
ALTER TABLE semantic_query DROP CONSTRAINT semantic_query_idempotency_key_key, ADD UNIQUE(tenant_id,idempotency_key),
    ADD FOREIGN KEY(tenant_id,project_id) REFERENCES semantic_project(tenant_id,project_id) ON DELETE CASCADE,
    ADD FOREIGN KEY(tenant_id,release_id) REFERENCES semantic_release(tenant_id,release_id),
    ADD FOREIGN KEY(tenant_id,build_id) REFERENCES build_ownership(tenant_id,build_id),
    ADD FOREIGN KEY(tenant_id,user_id) REFERENCES platform_user(tenant_id,id);
ALTER TABLE legacy_build_mapping
    ADD FOREIGN KEY(tenant_id,release_id) REFERENCES semantic_release(tenant_id,release_id),
    ADD FOREIGN KEY(tenant_id,build_id) REFERENCES build_ownership(tenant_id,build_id);

-- 已核验的迁移报告只允许关联指定租户的发布、构建与查询，重复导入保留原证据。
DROP FUNCTION import_legacy_build_mapping(uuid,uuid,text,text);
CREATE FUNCTION import_legacy_build_mapping(owner_tenant varchar, old_release uuid, new_build uuid, source_repository text, evidence text)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE prior legacy_build_mapping%ROWTYPE;
BEGIN
    IF nullif(trim(source_repository),'') IS NULL OR nullif(trim(evidence),'') IS NULL OR new_build IS NULL THEN
        RAISE EXCEPTION 'verified migration mapping and source evidence are required';
    END IF;
    PERFORM FROM semantic_release WHERE tenant_id=owner_tenant AND release_id=old_release FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'legacy release does not exist for tenant'; END IF;
    SELECT * INTO prior FROM legacy_build_mapping WHERE tenant_id=owner_tenant AND release_id=old_release;
    IF FOUND AND (prior.build_id<>new_build OR prior.repository<>source_repository OR prior.source_evidence<>evidence) THEN
        RAISE EXCEPTION 'legacy build mapping conflicts with existing evidence';
    END IF;
    INSERT INTO legacy_build_mapping(tenant_id,release_id,build_id,repository,source_evidence)
        VALUES(owner_tenant,old_release,new_build,source_repository,evidence) ON CONFLICT(release_id) DO NOTHING;
    UPDATE semantic_query SET build_id=new_build WHERE tenant_id=owner_tenant AND release_id=old_release AND build_id IS NULL;
    IF EXISTS (SELECT FROM semantic_query WHERE tenant_id=owner_tenant AND release_id=old_release AND build_id<>new_build) THEN
        RAISE EXCEPTION 'historical query already references a different build';
    END IF;
    RETURN true;
END $$;

-- 任一业务记录的租户归属在创建后固定，防止更新没有子记录的叶子资产时跨租户转移。
CREATE FUNCTION reject_tenant_reassignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'tenant ownership is immutable on %', TG_TABLE_NAME USING ERRCODE='23514';
END $$;
DO $$
DECLARE owned_table text;
BEGIN
    FOR owned_table IN SELECT name FROM tenant_backfill_tables
                       UNION ALL SELECT unnest(ARRAY['platform_tenant','platform_user','execution_environment','build_ownership'])
    LOOP
        EXECUTE format('CREATE TRIGGER immutable_tenant BEFORE UPDATE OF tenant_id ON %I FOR EACH ROW
            WHEN (OLD.tenant_id IS DISTINCT FROM NEW.tenant_id) EXECUTE FUNCTION reject_tenant_reassignment()', owned_table);
    END LOOP;
END $$;
