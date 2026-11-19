-- 仅供授权列表展示，查询与授予操作始终向服务核验当前版本。
CREATE SEQUENCE asset_catalog_scan_seq;
CREATE TABLE asset_catalog_observation (
    tenant_id varchar(8) NOT NULL REFERENCES platform_tenant,
    environment_id uuid NOT NULL,
    repository text NOT NULL,
    branch_name text NOT NULL,
    build_id uuid,
    scan_sequence bigint NOT NULL,
    PRIMARY KEY (tenant_id, environment_id, repository, branch_name),
    FOREIGN KEY (tenant_id, environment_id, build_id)
        REFERENCES build_ownership (tenant_id, environment_id, build_id)
);
COMMENT ON COLUMN asset_catalog_observation.tenant_id IS '观察结果所属租户';
COMMENT ON COLUMN asset_catalog_observation.environment_id IS '执行环境标识';
COMMENT ON COLUMN asset_catalog_observation.repository IS '部署仓库';
COMMENT ON COLUMN asset_catalog_observation.branch_name IS '部署分支';
COMMENT ON COLUMN asset_catalog_observation.build_id IS '最近观察到的生效且已登记的构建，无生效构建时为空';
COMMENT ON COLUMN asset_catalog_observation.scan_sequence IS '后台扫描序号，用于避免较早扫描覆盖新结果';
CREATE TRIGGER immutable_tenant BEFORE UPDATE OF tenant_id ON asset_catalog_observation FOR EACH ROW
    WHEN (OLD.tenant_id IS DISTINCT FROM NEW.tenant_id) EXECUTE FUNCTION reject_tenant_reassignment();
