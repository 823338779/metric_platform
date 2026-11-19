-- 构建登记和资源成员同事务完成，空目录也有明确的完成标记。
CREATE TABLE asset_build_scope (
 tenant_id varchar(8) NOT NULL REFERENCES platform_tenant,
 build_id uuid NOT NULL, environment_id uuid NOT NULL,
 repository text NOT NULL, branch_name text NOT NULL, indexed boolean NOT NULL DEFAULT false,
 PRIMARY KEY(tenant_id,build_id),
 FOREIGN KEY(tenant_id,environment_id,build_id) REFERENCES build_ownership(tenant_id,environment_id,build_id)
);
CREATE TABLE deployed_asset (
 tenant_id varchar(8) NOT NULL REFERENCES platform_tenant,
 asset_id uuid NOT NULL DEFAULT gen_random_uuid(), owner_id uuid NOT NULL,
 environment_id uuid NOT NULL, repository text NOT NULL, branch_name text NOT NULL,
 kind text NOT NULL CHECK(kind IN ('METRIC','DIMENSION','DATASET')),
 node_id text NOT NULL, dimension_name text NOT NULL,
 PRIMARY KEY(tenant_id,asset_id),
 UNIQUE(tenant_id,environment_id,repository,branch_name,kind,node_id,dimension_name),
 FOREIGN KEY(tenant_id,owner_id) REFERENCES platform_user(tenant_id,id)
);
-- 构建成员按版本写入；人员授权仅依赖稳定资产键。
CREATE TABLE asset_build_member (
 tenant_id varchar(8) NOT NULL REFERENCES platform_tenant,
 build_id uuid NOT NULL, asset_id uuid NOT NULL, query_name text NOT NULL, selector text NOT NULL,
 PRIMARY KEY(tenant_id,build_id,asset_id),
 FOREIGN KEY(tenant_id,build_id) REFERENCES asset_build_scope,
 FOREIGN KEY(tenant_id,asset_id) REFERENCES deployed_asset
);
CREATE TABLE asset_user_grant (
 tenant_id varchar(8) NOT NULL REFERENCES platform_tenant,
 user_id uuid NOT NULL, asset_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,user_id,asset_id),
 FOREIGN KEY(tenant_id,user_id) REFERENCES platform_user(tenant_id,id),
 FOREIGN KEY(tenant_id,asset_id) REFERENCES deployed_asset
);
COMMENT ON COLUMN asset_build_scope.tenant_id IS '租户归属';
COMMENT ON COLUMN asset_build_scope.build_id IS '服务固定构建';
COMMENT ON COLUMN asset_build_scope.environment_id IS '构建所属环境';
COMMENT ON COLUMN asset_build_scope.repository IS '规范仓库地址';
COMMENT ON COLUMN asset_build_scope.branch_name IS '部署分支';
COMMENT ON COLUMN asset_build_scope.indexed IS '完整授权目录已登记';
CREATE TRIGGER immutable_tenant BEFORE UPDATE OF tenant_id ON asset_build_scope FOR EACH ROW
 WHEN (OLD.tenant_id IS DISTINCT FROM NEW.tenant_id) EXECUTE FUNCTION reject_tenant_reassignment();
COMMENT ON COLUMN deployed_asset.tenant_id IS '租户归属';
COMMENT ON COLUMN deployed_asset.asset_id IS '平台稳定授权标识';
COMMENT ON COLUMN deployed_asset.owner_id IS '租户管理者账号';
COMMENT ON COLUMN deployed_asset.environment_id IS '部署环境';
COMMENT ON COLUMN deployed_asset.repository IS '规范仓库地址';
COMMENT ON COLUMN deployed_asset.branch_name IS '部署分支';
COMMENT ON COLUMN deployed_asset.kind IS '资产类型';
COMMENT ON COLUMN deployed_asset.node_id IS 'dbt 原生节点标识';
COMMENT ON COLUMN deployed_asset.dimension_name IS '模型内维度原名，其他类型为空串';
CREATE TRIGGER immutable_tenant BEFORE UPDATE OF tenant_id ON deployed_asset FOR EACH ROW
 WHEN (OLD.tenant_id IS DISTINCT FROM NEW.tenant_id) EXECUTE FUNCTION reject_tenant_reassignment();
COMMENT ON COLUMN asset_build_member.tenant_id IS '租户归属';
COMMENT ON COLUMN asset_build_member.build_id IS '存在此资产的构建';
COMMENT ON COLUMN asset_build_member.asset_id IS '稳定资产身份';
COMMENT ON COLUMN asset_build_member.query_name IS '指标或所属语义模型的原生名称';
COMMENT ON COLUMN asset_build_member.selector IS '数据集精确 FQN 选择器';
CREATE TRIGGER immutable_tenant BEFORE UPDATE OF tenant_id ON asset_build_member FOR EACH ROW
 WHEN (OLD.tenant_id IS DISTINCT FROM NEW.tenant_id) EXECUTE FUNCTION reject_tenant_reassignment();
COMMENT ON COLUMN asset_user_grant.tenant_id IS '租户归属';
COMMENT ON COLUMN asset_user_grant.user_id IS '获得使用权的用户';
COMMENT ON COLUMN asset_user_grant.asset_id IS '被授权资产';
CREATE TRIGGER immutable_tenant BEFORE UPDATE OF tenant_id ON asset_user_grant FOR EACH ROW
 WHEN (OLD.tenant_id IS DISTINCT FROM NEW.tenant_id) EXECUTE FUNCTION reject_tenant_reassignment();
