CREATE TABLE platform_tenant (
    id UUID PRIMARY KEY,
    tenant_key VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP
);

INSERT INTO platform_tenant(id, tenant_key, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'default', 'Default tenant')
ON CONFLICT (tenant_key) DO NOTHING;

ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS display_name VARCHAR(160);
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS password_algorithm VARCHAR(40) NOT NULL DEFAULT 'bcrypt';
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS auth_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS failed_login_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
ALTER TABLE platform_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

UPDATE platform_user
SET tenant_id = '00000000-0000-0000-0000-000000000001',
    display_name = COALESCE(display_name, username)
WHERE tenant_id IS NULL OR display_name IS NULL;

ALTER TABLE platform_user ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE platform_user ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE platform_user
    ADD CONSTRAINT fk_platform_user_tenant
    FOREIGN KEY (tenant_id) REFERENCES platform_tenant(id);
ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS platform_user_username_key;
ALTER TABLE platform_user
    ADD CONSTRAINT uq_platform_user_tenant_username UNIQUE (tenant_id, username);
ALTER TABLE platform_user
    ADD CONSTRAINT uq_platform_user_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE platform_workspace (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform_tenant(id) ON DELETE CASCADE,
    workspace_key VARCHAR(120) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    UNIQUE (tenant_id, workspace_key),
    UNIQUE (id, tenant_id)
);

CREATE TABLE platform_user_workspace (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    FOREIGN KEY (identity_id, tenant_id)
        REFERENCES platform_user(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, tenant_id)
        REFERENCES platform_workspace(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (identity_id, workspace_id)
);

CREATE TABLE platform_identity_dataset_grant (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform_tenant(id) ON DELETE CASCADE,
    identity_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    discover_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    query_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    FOREIGN KEY (identity_id, tenant_id)
        REFERENCES platform_user(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (identity_id, dataset_id)
);

CREATE INDEX idx_identity_dataset_grant_discover
    ON platform_identity_dataset_grant(identity_id, dataset_id)
    WHERE discover_allowed = TRUE AND is_deleted = FALSE;
CREATE INDEX idx_identity_dataset_grant_query
    ON platform_identity_dataset_grant(identity_id, dataset_id)
    WHERE query_allowed = TRUE AND is_deleted = FALSE;

CREATE TABLE platform_access_token (
    id UUID PRIMARY KEY,
    token_hash CHAR(64) NOT NULL UNIQUE,
    identity_id UUID NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    audience VARCHAR(80) NOT NULL,
    auth_version BIGINT NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_platform_access_token_identity_expiry
    ON platform_access_token(identity_id, expires_at);

ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS effective_identity_id UUID;
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS client_id VARCHAR(120);
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS outcome VARCHAR(24) NOT NULL DEFAULT 'SUCCEEDED';
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS detail_json TEXT NOT NULL DEFAULT '{}';
