ALTER TABLE data_source ADD COLUMN password TEXT;

UPDATE data_source
SET status = 'INACTIVE',
    health_status = 'UNKNOWN',
    last_checked_at = NULL,
    last_latency_ms = NULL,
    password = NULL;

ALTER TABLE data_source
    DROP COLUMN secret_reference,
    DROP COLUMN encrypted_password,
    DROP COLUMN password_nonce;
