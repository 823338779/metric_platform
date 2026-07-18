UPDATE data_source SET password = '' WHERE password IS NULL;

ALTER TABLE data_source ALTER COLUMN password SET DEFAULT '';
ALTER TABLE data_source ALTER COLUMN password SET NOT NULL;
