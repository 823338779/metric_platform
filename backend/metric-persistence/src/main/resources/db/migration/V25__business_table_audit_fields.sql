-- Wrapper 更新、原子 upsert 和按复合键删除不会触发实体自动填充。
CREATE FUNCTION maintain_business_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        NEW.created_at := OLD.created_at;
        IF NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
            NEW.updated_at := statement_timestamp();
        END IF;
    END IF;
    IF NOT NEW.is_deleted THEN
        NEW.deleted_at := NULL;
    ELSIF NEW.deleted_at IS NULL THEN
        NEW.deleted_at := NEW.updated_at;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE
    business_table text;
BEGIN
    FOREACH business_table IN ARRAY ARRAY[
        'platform_tenant', 'platform_user', 'platform_access_token', 'audit_event',
        'execution_environment', 'execution_environment_version', 'execution_namespace_owner',
        'environment_build_submission', 'build_ownership', 'query_execution_audit',
        'asset_build_scope', 'deployed_asset', 'asset_build_member', 'asset_user_grant',
        'asset_catalog_observation', 'semantic_project', 'semantic_release', 'semantic_resource',
        'semantic_dependency', 'semantic_query', 'legacy_build_mapping', 'build_query_audit',
        'build_history', 'deployment_history', 'history_cursor', 'agent_branch_cache',
        'tenant_cutover_submission_archive'
    ] LOOP
        IF business_table = 'tenant_cutover_submission_archive' AND to_regclass(business_table) IS NULL THEN
            CONTINUE;
        END IF;
        EXECUTE format('ALTER TABLE %I
            ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
            ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
            ADD COLUMN IF NOT EXISTS is_deleted boolean NOT NULL DEFAULT false,
            ADD COLUMN IF NOT EXISTS deleted_at timestamptz', business_table);
        IF business_table = 'tenant_cutover_submission_archive' THEN
            -- CREATE TABLE AS 归档不会继承原列的默认值和非空约束。
            EXECUTE 'UPDATE tenant_cutover_submission_archive SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL';
            EXECUTE 'ALTER TABLE tenant_cutover_submission_archive ALTER COLUMN created_at SET NOT NULL,
                ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP';
        END IF;
        CASE business_table
            WHEN 'query_execution_audit' THEN
                UPDATE query_execution_audit SET created_at = completed_at, updated_at = completed_at;
            WHEN 'build_query_audit' THEN
                UPDATE build_query_audit SET created_at = submitted_at, updated_at = submitted_at;
            WHEN 'semantic_query' THEN
                UPDATE semantic_query SET created_at = submitted_at, updated_at = coalesce(completed_at, submitted_at);
            WHEN 'execution_environment_version' THEN
                UPDATE execution_environment_version SET updated_at = created_at;
            ELSE NULL;
        END CASE;
        IF business_table IN ('platform_user', 'platform_access_token', 'audit_event') THEN
            -- 旧逻辑删除缺少删除时间时，使用该行已有的最后更新时间。
            EXECUTE format('UPDATE %I SET deleted_at = updated_at WHERE is_deleted AND deleted_at IS NULL',
                business_table);
        END IF;
        EXECUTE format('CREATE TRIGGER maintain_audit BEFORE INSERT OR UPDATE ON %I
            FOR EACH ROW EXECUTE FUNCTION maintain_business_audit()', business_table);
    END LOOP;
END $$;
