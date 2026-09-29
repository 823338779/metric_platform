CREATE TABLE semantic_project (
    project_id VARCHAR(80) PRIMARY KEY,
    repository_id VARCHAR(160) NOT NULL,
    project_subdir VARCHAR(400) NOT NULL,
    profile_binding_id VARCHAR(160) NOT NULL,
    observed_sha VARCHAR(64),
    observed_project_digest VARCHAR(64),
    observed_config_version VARCHAR(160),
    last_observation_status VARCHAR(40),
    last_observed_at TIMESTAMPTZ,
    active_release_id UUID,
    publication_sequence BIGINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE semantic_project IS '固定 Git 与 profile 绑定的 dbt 项目及原子生效指针';
COMMENT ON COLUMN semantic_project.project_id IS '部署配置中的稳定 dbt 项目标识';
COMMENT ON COLUMN semantic_project.repository_id IS '固定 Git 仓库绑定标识';
COMMENT ON COLUMN semantic_project.project_subdir IS '仓库内固定 dbt 项目子目录';
COMMENT ON COLUMN semantic_project.profile_binding_id IS '外部服务管理的固定 StarRocks profile 绑定';
COMMENT ON COLUMN semantic_project.observed_sha IS '最近实际读取的远端 main 提交 SHA';
COMMENT ON COLUMN semantic_project.observed_project_digest IS '最近提交中受支持 dbt 输入的 Git 树摘要';
COMMENT ON COLUMN semantic_project.observed_config_version IS '最近观测时的受控构建配置版本';
COMMENT ON COLUMN semantic_project.last_observation_status IS '最近观测的跳过或候选状态';
COMMENT ON COLUMN semantic_project.last_observed_at IS '最近读取远端 main 的时间';
COMMENT ON COLUMN semantic_project.active_release_id IS '当前已发布且对默认查询可见的版本';
COMMENT ON COLUMN semantic_project.publication_sequence IS '项目内单调递增的候选序号，用于发布判旧';

CREATE TABLE semantic_release (
    release_id UUID PRIMARY KEY,
    project_id VARCHAR(80) NOT NULL REFERENCES semantic_project(project_id) ON DELETE CASCADE,
    release_sequence BIGINT NOT NULL,
    expected_active_release_id UUID,
    target_commit_sha VARCHAR(64) NOT NULL,
    build_source_sha VARCHAR(64) NOT NULL,
    project_digest VARCHAR(64) NOT NULL,
    config_version VARCHAR(160) NOT NULL,
    expected_toolchain_version VARCHAR(160),
    toolchain_version VARCHAR(160),
    idempotency_key UUID NOT NULL,
    submission_attempted BOOLEAN NOT NULL DEFAULT FALSE,
    transient_retry_count INTEGER NOT NULL DEFAULT 0 CHECK (transient_retry_count >= 0),
    retry_after TIMESTAMPTZ,
    runtime_run_id UUID,
    schema_name VARCHAR(160),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PREPARING','BUILDING','VALIDATING','READY','PUBLISHED','FAILED','SUPERSEDED')),
    cleanup_state VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (cleanup_state IN ('PENDING','CLEANING','CLEANED')),
    validation_json JSONB,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    UNIQUE(project_id, release_sequence)
);
COMMENT ON TABLE semantic_release IS '每个固定目标 SHA 的构建候选与发布结果';
COMMENT ON COLUMN semantic_release.release_id IS '平台生成的不可变发布版本标识';
COMMENT ON COLUMN semantic_release.project_id IS '所属固定 dbt 项目';
COMMENT ON COLUMN semantic_release.release_sequence IS '项目内候选顺序，用于过时判定';
COMMENT ON COLUMN semantic_release.expected_active_release_id IS '候选创建时的生效指针，用于事务内 CAS 判旧';
COMMENT ON COLUMN semantic_release.target_commit_sha IS '该发布记录对应的最新 main SHA';
COMMENT ON COLUMN semantic_release.build_source_sha IS '实际构建来源 SHA；README-only 复用时可不同于目标 SHA';
COMMENT ON COLUMN semantic_release.project_digest IS '构建使用的 dbt 项目输入摘要';
COMMENT ON COLUMN semantic_release.config_version IS 'profile、环境与工具链等受控构建输入的版本';
COMMENT ON COLUMN semantic_release.expected_toolchain_version IS '本候选要求的 dbt/MetricFlow 工具链版本，与 READY 产物逐项比较';
COMMENT ON COLUMN semantic_release.toolchain_version IS '服务报告的 dbt 和 MetricFlow 工具链版本';
COMMENT ON COLUMN semantic_release.idempotency_key IS '提交构建任务的幂等键，超时后可找回任务';
COMMENT ON COLUMN semantic_release.submission_attempted IS '是否已在调用服务前持久记录一次提交尝试';
COMMENT ON COLUMN semantic_release.transient_retry_count IS '瞬时服务故障已使用的自动重试次数';
COMMENT ON COLUMN semantic_release.retry_after IS '下次允许自动重试的时间，避免密集请求故障服务';
COMMENT ON COLUMN semantic_release.runtime_run_id IS '外部运行服务的构建任务标识';
COMMENT ON COLUMN semantic_release.schema_name IS '本候选独占的 StarRocks schema 名';
COMMENT ON COLUMN semantic_release.state IS '候选构建与发布状态';
COMMENT ON COLUMN semantic_release.cleanup_state IS 'schema 清理认领与完成状态';
COMMENT ON COLUMN semantic_release.validation_json IS 'READY 产物的无凭据验证摘要';
COMMENT ON COLUMN semantic_release.error_code IS '脱敏后的确定性或瞬时错误码';
COMMENT ON COLUMN semantic_release.created_at IS '候选首次创建时间';
COMMENT ON COLUMN semantic_release.updated_at IS '候选最近状态更新时间';
COMMENT ON COLUMN semantic_release.published_at IS '生效指针成功切换时间';
CREATE INDEX semantic_release_project_digest ON semantic_release(project_id, project_digest, config_version);
CREATE INDEX semantic_release_project_state ON semantic_release(project_id, state, release_sequence DESC);
CREATE INDEX semantic_release_idempotency ON semantic_release(idempotency_key);
ALTER TABLE semantic_project ADD CONSTRAINT semantic_project_active_release_fk FOREIGN KEY (active_release_id) REFERENCES semantic_release(release_id);

CREATE TABLE semantic_resource (
    release_id UUID NOT NULL REFERENCES semantic_release(release_id) ON DELETE CASCADE,
    resource_id VARCHAR(240) NOT NULL,
    native_id VARCHAR(400) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    name VARCHAR(240) NOT NULL,
    description TEXT,
    search_text TEXT,
    definition_json JSONB NOT NULL,
    source_path VARCHAR(800),
    queryable BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(release_id, resource_id)
);
COMMENT ON TABLE semantic_resource IS '不可编辑的版本化 dbt/MetricFlow 目录投影';
COMMENT ON COLUMN semantic_resource.release_id IS '资源所属的发布版本';
COMMENT ON COLUMN semantic_resource.resource_id IS '跨版本稳定、与 releaseId 无关的逻辑资源标识';
COMMENT ON COLUMN semantic_resource.native_id IS 'dbt 或 MetricFlow 原生资源标识';
COMMENT ON COLUMN semantic_resource.kind IS '模型、语义模型、维度、指标等原生资源类型';
COMMENT ON COLUMN semantic_resource.name IS '资源显示名称';
COMMENT ON COLUMN semantic_resource.description IS '原生定义中的说明';
COMMENT ON COLUMN semantic_resource.search_text IS '目录检索用的归一化文本';
COMMENT ON COLUMN semantic_resource.definition_json IS '原生定义的只读 JSON 投影';
COMMENT ON COLUMN semantic_resource.source_path IS 'Git 中的定义来源路径';
COMMENT ON COLUMN semantic_resource.queryable IS '此版本是否支持该资源的查询';
CREATE INDEX semantic_resource_search ON semantic_resource(release_id, kind, name);

CREATE TABLE semantic_dependency (
    release_id UUID NOT NULL REFERENCES semantic_release(release_id) ON DELETE CASCADE,
    upstream_resource_id VARCHAR(240) NOT NULL,
    downstream_resource_id VARCHAR(240) NOT NULL,
    dependency_kind VARCHAR(40) NOT NULL,
    PRIMARY KEY(release_id, upstream_resource_id, downstream_resource_id, dependency_kind),
    FOREIGN KEY (release_id, upstream_resource_id) REFERENCES semantic_resource(release_id, resource_id),
    FOREIGN KEY (release_id, downstream_resource_id) REFERENCES semantic_resource(release_id, resource_id)
);
COMMENT ON TABLE semantic_dependency IS '同一发布版本内的资源血缘边';
COMMENT ON COLUMN semantic_dependency.release_id IS '依赖边所属的发布版本';
COMMENT ON COLUMN semantic_dependency.upstream_resource_id IS '上游资源稳定标识';
COMMENT ON COLUMN semantic_dependency.downstream_resource_id IS '下游资源稳定标识';
COMMENT ON COLUMN semantic_dependency.dependency_kind IS '模型引用、语义关联等依赖类型';

CREATE TABLE semantic_query (
    query_id UUID PRIMARY KEY,
    project_id VARCHAR(80) NOT NULL REFERENCES semantic_project(project_id) ON DELETE CASCADE,
    release_id UUID NOT NULL REFERENCES semantic_release(release_id),
    idempotency_key UUID NOT NULL UNIQUE,
    runtime_query_id UUID,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('QUERY','EXPLAIN','PREVIEW')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')),
    result_locator VARCHAR(800),
    error_code VARCHAR(80),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
COMMENT ON TABLE semantic_query IS '固定发布版本的异步查询、解释与预览任务';
COMMENT ON COLUMN semantic_query.query_id IS '平台生成的查询任务标识';
COMMENT ON COLUMN semantic_query.project_id IS '查询所属项目，用于拒绝跨项目任务访问';
COMMENT ON COLUMN semantic_query.release_id IS '入口处固定且不可改写的发布版本';
COMMENT ON COLUMN semantic_query.idempotency_key IS '外部查询提交的幂等键';
COMMENT ON COLUMN semantic_query.runtime_query_id IS '外部运行服务的查询任务标识';
COMMENT ON COLUMN semantic_query.mode IS '查询、解释或数据集预览模式';
COMMENT ON COLUMN semantic_query.status IS '持久化的异步任务状态';
COMMENT ON COLUMN semantic_query.result_locator IS '完成结果的无凭据定位符';
COMMENT ON COLUMN semantic_query.error_code IS '脱敏后的查询错误码';
COMMENT ON COLUMN semantic_query.submitted_at IS '平台接收查询任务的时间';
COMMENT ON COLUMN semantic_query.completed_at IS '任务到达终态的时间';
CREATE INDEX semantic_query_release_status ON semantic_query(release_id, status);
