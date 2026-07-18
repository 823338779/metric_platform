UPDATE platform_user SET username = LOWER(TRIM(username));

ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS uq_platform_user_tenant_username;
ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS platform_user_username_key;
ALTER TABLE platform_user
    ADD CONSTRAINT uq_platform_user_username UNIQUE (username);
