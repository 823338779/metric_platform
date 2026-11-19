-- 平台只持有授权身份。仓库、分支和原生节点从具体构建读取。
ALTER TABLE deployed_asset ADD COLUMN canonical_name text;
COMMENT ON COLUMN deployed_asset.canonical_name IS '指标名、数据集名或维度所属语义模型名，按 dbt 原生名称精确匹配';
ALTER TABLE asset_build_member ADD COLUMN node_id text;
COMMENT ON COLUMN asset_build_member.node_id IS '此构建中的 dbt 原生节点标识，维度使用所属语义模型的节点标识';
UPDATE asset_build_member m SET node_id=a.node_id FROM deployed_asset a
WHERE (m.tenant_id,m.asset_id)=(a.tenant_id,a.asset_id);
ALTER TABLE asset_build_member ALTER COLUMN node_id SET NOT NULL;

-- 每个旧授权必须有且只有一个可核验的名字；缺少成员或名字矛盾时整笔迁移回滚。
UPDATE deployed_asset a SET canonical_name=(
    SELECT DISTINCT m.query_name FROM asset_build_member m
    WHERE (m.tenant_id,m.asset_id)=(a.tenant_id,a.asset_id)
);
ALTER TABLE deployed_asset ALTER COLUMN canonical_name SET NOT NULL;
CREATE TEMP TABLE asset_merge ON COMMIT DROP AS
SELECT tenant_id,asset_id,first_value(asset_id) OVER (
    PARTITION BY tenant_id,environment_id,kind,canonical_name,dimension_name ORDER BY asset_id
) AS retained_id FROM deployed_asset;

-- 合并同一环境的授权并保留每个固定构建的原生节点及选择器，其他环境始终独立。
INSERT INTO asset_user_grant(tenant_id,user_id,asset_id)
SELECT g.tenant_id,g.user_id,k.retained_id FROM asset_user_grant g JOIN asset_merge k USING(tenant_id,asset_id)
WHERE k.asset_id<>k.retained_id ON CONFLICT DO NOTHING;
DELETE FROM asset_user_grant g USING asset_merge k
WHERE (g.tenant_id,g.asset_id)=(k.tenant_id,k.asset_id) AND k.asset_id<>k.retained_id;
UPDATE asset_build_member m SET asset_id=k.retained_id FROM asset_merge k
WHERE (m.tenant_id,m.asset_id)=(k.tenant_id,k.asset_id) AND k.asset_id<>k.retained_id;
DELETE FROM deployed_asset a USING asset_merge k
WHERE (a.tenant_id,a.asset_id)=(k.tenant_id,k.asset_id) AND k.asset_id<>k.retained_id;
ALTER TABLE deployed_asset DROP COLUMN repository, DROP COLUMN branch_name, DROP COLUMN node_id,
    ADD UNIQUE(tenant_id,environment_id,kind,canonical_name,dimension_name);

-- 历史观察可能同时指向一个环境的多个分支，必须重新向发布服务核验。
DELETE FROM asset_catalog_observation;
ALTER TABLE asset_catalog_observation DROP CONSTRAINT asset_catalog_observation_pkey,
    DROP COLUMN repository, DROP COLUMN branch_name, ADD PRIMARY KEY(tenant_id,environment_id);
