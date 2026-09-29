-- 旧版人工目录和 Git 对象同步已没有运行时读写路径。非空表先由维护人员备份和清空，
-- 防止应用升级时自动销毁仍需保留的历史数据。
DO $$
DECLARE
    legacy_table TEXT;
    has_rows BOOLEAN;
BEGIN
    FOREACH legacy_table IN ARRAY ARRAY[
        'data_source', 'dataset', 'dataset_field', 'dimension', 'metric',
        'dataset_relationship', 'dataset_relationship_field_pair',
        'dimension_time_granularity', 'git_sync_state', 'git_object_ownership'
    ] LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', legacy_table) INTO has_rows;
        IF has_rows THEN
            RAISE EXCEPTION '旧目录表 % 仍有数据；备份并清空后再执行 V14', legacy_table;
        END IF;
    END LOOP;
END
$$;

-- 按外键依赖顺序移除旧目录；当前发布目录使用 semantic_* 表。
DROP TABLE dimension_time_granularity;
DROP TABLE dataset_relationship_field_pair;
DROP TABLE dataset_relationship;
DROP TABLE dimension;
DROP TABLE metric;
DROP TABLE dataset_field;
DROP TABLE dataset;
DROP TABLE data_source;
DROP TABLE git_object_ownership;
DROP TABLE git_sync_state;
DROP FUNCTION guard_git_semantic_write();
