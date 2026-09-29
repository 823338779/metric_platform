ALTER TABLE data_source
    ADD COLUMN description TEXT,
    ADD COLUMN database_type VARCHAR(32) NOT NULL DEFAULT 'STARROCKS',
    ADD COLUMN driver_class VARCHAR(255),
    ADD COLUMN encrypted_password TEXT,
    ADD COLUMN password_nonce VARCHAR(64),
    ADD COLUMN default_catalog VARCHAR(255),
    ADD COLUMN default_schema VARCHAR(255),
    ADD COLUMN health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN last_checked_at TIMESTAMP,
    ADD COLUMN last_latency_ms BIGINT,
    ADD COLUMN deleted_at TIMESTAMP,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE data_source ALTER COLUMN secret_reference DROP NOT NULL;
ALTER TABLE data_source ALTER COLUMN database_name DROP NOT NULL;

UPDATE data_source
SET database_type = 'STARROCKS',
    status = 'ACTIVE',
    default_catalog = database_name
WHERE status = 'ENABLED';
