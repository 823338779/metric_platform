DROP TABLE IF EXISTS platform_identity_dataset_grant;
DROP TABLE IF EXISTS platform_user_workspace;
DROP TABLE IF EXISTS platform_workspace;

ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS fk_platform_user_tenant;
ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS uq_platform_user_id_tenant;
ALTER TABLE platform_user DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE platform_user DROP COLUMN IF EXISTS role_code;

ALTER TABLE platform_access_token DROP COLUMN IF EXISTS audience;
ALTER TABLE audit_event DROP COLUMN IF EXISTS client_id;

DROP TABLE IF EXISTS platform_tenant;
DROP TABLE IF EXISTS api_client;
