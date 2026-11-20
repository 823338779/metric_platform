-- 已确认的旧提交可恢复部署目标；同一构建的目标有歧义时留给服务核验。
WITH targets AS (
    SELECT DISTINCT tenant_id,build_id,environment_id,command->>'repository' AS repository,
           command->>'branchName' AS branch_name
    FROM environment_build_submission
    WHERE build_id IS NOT NULL AND command->>'repository' IS NOT NULL AND command->>'branchName' IS NOT NULL
)
INSERT INTO asset_build_scope(tenant_id,build_id,environment_id,repository,branch_name)
SELECT tenant_id,build_id,environment_id,min(repository),min(branch_name)
FROM targets GROUP BY tenant_id,build_id,environment_id HAVING count(*)=1
ON CONFLICT(tenant_id,build_id) DO NOTHING;
