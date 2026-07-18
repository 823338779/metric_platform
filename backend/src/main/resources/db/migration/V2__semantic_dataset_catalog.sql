ALTER TABLE dataset ADD COLUMN description VARCHAR(1000);
ALTER TABLE dataset ADD COLUMN object_type VARCHAR(16) NOT NULL DEFAULT 'TABLE';
ALTER TABLE dataset ADD COLUMN filter_expression TEXT;
ALTER TABLE dataset ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE dataset_field ADD COLUMN selected BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE dataset_field ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dataset_field ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE dimension ADD COLUMN dimension_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE dimension ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE metric ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE dataset_relationship (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    source_dataset_id UUID NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    target_dataset_id UUID NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    cardinality VARCHAR(24) NOT NULL,
    last_validated_at TIMESTAMP,
    last_validation_status VARCHAR(24),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_dataset_id, name),
    CHECK(source_dataset_id <> target_dataset_id)
);

CREATE TABLE dataset_relationship_field_pair (
    relationship_id UUID NOT NULL REFERENCES dataset_relationship(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    source_field_id UUID NOT NULL REFERENCES dataset_field(id),
    target_field_id UUID NOT NULL REFERENCES dataset_field(id),
    PRIMARY KEY(relationship_id, ordinal)
);

CREATE TABLE dimension_time_granularity (
    dimension_id UUID NOT NULL REFERENCES dimension(id) ON DELETE CASCADE,
    granularity VARCHAR(16) NOT NULL,
    PRIMARY KEY(dimension_id, granularity)
);
