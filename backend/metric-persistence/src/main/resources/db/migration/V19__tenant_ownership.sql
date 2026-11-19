-- 编号只递增，回滚允许跳号，达到上限后禁止复用。
CREATE SEQUENCE tenant_number MINVALUE 1 MAXVALUE 99999 NO CYCLE;
CREATE TABLE platform_tenant (
    tenant_id varchar(8) PRIMARY KEY CHECK (tenant_id ~ '^tn_[0-9]{5}$' AND tenant_id <> 'tn_00000'),
    manager_id uuid NOT NULL UNIQUE
);
COMMENT ON COLUMN platform_tenant.tenant_id IS '运维开通租户时分配的稳定编号';
COMMENT ON COLUMN platform_tenant.manager_id IS '本租户唯一管理者账号';
ALTER TABLE platform_user ADD COLUMN tenant_id varchar(8) REFERENCES platform_tenant;
ALTER TABLE execution_environment ADD COLUMN tenant_id varchar(8) REFERENCES platform_tenant;
COMMENT ON COLUMN platform_user.tenant_id IS '账号唯一所属租户';
COMMENT ON COLUMN execution_environment.tenant_id IS '环境及其配置版本所属租户';
CREATE TABLE build_ownership (
    build_id uuid PRIMARY KEY,
    environment_id uuid NOT NULL,
    tenant_id varchar(8) NOT NULL REFERENCES platform_tenant
);
COMMENT ON COLUMN build_ownership.build_id IS '服务已确认或迁移清单已核验的构建标识';
COMMENT ON COLUMN build_ownership.environment_id IS '固定构建的来源环境，可为历史外部环境';
COMMENT ON COLUMN build_ownership.tenant_id IS '固定构建及其派生资产的租户';

-- 存量库必须先准备冻结后的完整清单。新库由运维开通第一个租户。
DO $migration$
DECLARE
    chosen uuid;
    inventory record;
BEGIN
    IF to_regclass('tenant_cutover_input') IS NULL THEN
        IF EXISTS (SELECT FROM platform_user) OR EXISTS (SELECT FROM execution_environment)
            OR EXISTS (SELECT FROM environment_build_submission) THEN
            RAISE EXCEPTION 'tenant migration inventory required';
        END IF;
        RETURN;
    END IF;
    SELECT * INTO STRICT inventory FROM tenant_cutover_input;
    SELECT id INTO STRICT chosen FROM platform_user
        WHERE username = lower(trim(inventory.manager_login)) AND status = 'ACTIVE'
          AND NOT is_deleted AND nullif(password_hash,'') IS NOT NULL
          AND (locked_until IS NULL OR locked_until <= now());
    IF inventory.environment_ids IS NULL OR jsonb_typeof(inventory.builds) IS DISTINCT FROM 'array'
        OR EXISTS (SELECT environment_id FROM execution_environment EXCEPT SELECT unnest(inventory.environment_ids))
        OR EXISTS (SELECT FROM environment_build_submission WHERE build_id IS NULL) THEN
        RAISE EXCEPTION 'tenant migration inventory incomplete or submission unresolved';
    END IF;
    INSERT INTO platform_tenant VALUES ('tn_00001', chosen);
    UPDATE platform_user SET tenant_id = 'tn_00001';
    UPDATE execution_environment SET tenant_id = 'tn_00001';
    INSERT INTO build_ownership(build_id,environment_id,tenant_id)
        SELECT build_id,environment_id,'tn_00001'
        FROM jsonb_to_recordset(inventory.builds) AS item(build_id uuid, environment_id uuid);
    IF EXISTS (SELECT environment_id FROM build_ownership EXCEPT SELECT unnest(inventory.environment_ids))
        OR EXISTS (SELECT build_id,environment_id FROM environment_build_submission
                   EXCEPT SELECT build_id,environment_id FROM build_ownership) THEN
        RAISE EXCEPTION 'tenant migration inventory conflicts with existing builds';
    END IF;
    -- 旧版审计和映射也提供可信构建 ID，遗漏外部环境必须在放流前发现。
    IF EXISTS (SELECT build_id FROM (
        SELECT build_id FROM build_history UNION SELECT build_id FROM legacy_build_mapping
        UNION SELECT build_id FROM build_query_audit UNION SELECT build_id FROM query_execution_audit
        UNION SELECT build_id FROM semantic_query WHERE build_id IS NOT NULL
    ) known EXCEPT SELECT build_id FROM build_ownership) THEN
        RAISE EXCEPTION 'tenant migration inventory missing historical build IDs; check external environments';
    END IF;
    PERFORM setval('tenant_number', 1, true);
    DROP TABLE tenant_cutover_input;
END
$migration$;

-- 事务结束时必须存在同租户的管理者，允许在事务中先插入租户再创建账号。
ALTER TABLE platform_user ALTER COLUMN tenant_id SET NOT NULL,
    ADD CONSTRAINT platform_user_tenant_identity UNIQUE(tenant_id,id);
ALTER TABLE platform_tenant ADD CONSTRAINT tenant_manager_in_tenant
    FOREIGN KEY(tenant_id,manager_id) REFERENCES platform_user(tenant_id,id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE execution_environment ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX environment_by_tenant ON execution_environment(tenant_id,created_at,environment_id);
CREATE INDEX build_by_tenant_environment ON build_ownership(tenant_id,environment_id);
-- 每租户单独签名分页游标，服务重启或多实例部署时仍可校验。
ALTER TABLE platform_tenant ADD COLUMN cursor_secret uuid NOT NULL DEFAULT gen_random_uuid();
COMMENT ON COLUMN platform_tenant.cursor_secret IS '仅平台使用的历史游标签名密钥';
