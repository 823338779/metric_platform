import { createHttpClient, type Fetcher } from './httpClient';

export type Environment = 'PREVIEW' | 'PRODUCTION';
export type ServiceError = {
  code: string;
  message: string;
  retryable: boolean;
  phase?: string;
  buildId?: string;
};
/** 仓库、环境和真实分支构成部署自然键，项目身份仅由 Agent 管理。 */
export type DeploymentKey = { repository: string; environment: Environment; branchName: string };
export type BuildContext = DeploymentKey & { agentProjectId?: string; buildId: string };
export type DevelopmentProject = {
  projectId: string;
  name: string;
  version: number;
  developmentConfigured: boolean;
  repository: string | null;
};
export type BranchList = {
  items: { branchName: string; headSha: string }[];
  observedAt: string | null;
  cached: boolean;
  source: string;
};
export type DeploymentAttempt = DeploymentKey & {
  generation: number;
  buildId: string;
  deploymentStatus: string;
  reason: string | null;
  createdAt: string;
  deployedAt: string | null;
};
export type DeploymentTarget = DeploymentKey & {
  version: number;
  desiredGeneration: number;
  activeBuildId: string | null;
  activeCommitSha: string | null;
  latestAttempt: DeploymentAttempt | null;
  observedHeadSha: string | null;
  headObservedAt: string | null;
  sourceState: string;
};
/** 构建成功、目录可读和物理查询可用是独立事实。 */
export type BuildView = DeploymentKey & {
  buildId: string;
  requestedCommitSha: string | null;
  commitSha: string | null;
  executionBinding: string;
  configVersion: string;
  toolchainVersion: string;
  buildStatus: string;
  phase: string;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
  cancelRequested: boolean;
  catalogAvailable: boolean;
  queryAvailable: boolean;
  queryUnavailableReason: string | null;
  error: ServiceError | null;
  initialDeployment: DeploymentAttempt | null;
};
export type BuildPage = {
  items: BuildView[];
  nextCursor: string | null;
  source?: string;
  syncedAt?: string | null;
};
export type PublishedResource = {
  resourceId: string;
  nativeId: string;
  kind: 'METRIC' | 'DIMENSION' | 'SEMANTIC_MODEL' | 'TABLE' | 'VIEW';
  name: string;
  displayName: string;
  description: string | null;
  sourcePath: string | null;
  capabilities: string[];
  attributes: Record<string, unknown>;
  nativeDetails: Record<string, unknown>;
};
export type PublishedOption = {
  optionId: string;
  resourceId: string | null;
  displayName: string;
  granularities: string[];
  operators: string[];
};
/** buildId 只在 URL 中，数值筛选值与时间字符串不在调用方转换精度。 */
export type PublishedQuery = {
  idempotencyKey: string;
  mode: 'QUERY' | 'EXPLAIN' | 'PREVIEW' | 'DIMENSION_VALUES';
  metricResourceIds: string[];
  groupBy?: { optionId: string; grain?: string }[];
  orderBy?: { fieldId: string; direction: 'ASC' | 'DESC' }[];
  filters?: { optionId: string; operator: string; value: unknown }[];
  startTime?: string;
  endTime?: string;
  limit?: number;
  datasetResourceId?: string;
  dimensionOptionId?: string;
};
export type OptionsTask = {
  optionsTaskId: string;
  buildId: string;
  state: string;
  metricResourceIds: string[];
  options: PublishedOption[];
  error: ServiceError | null;
};
export type PublishedResult = {
  queryId: string;
  buildId: string;
  state: string;
  mode?: PublishedQuery['mode'];
  commitSha?: string;
  businessTimezone?: string;
  normalizedTimeRange?: Record<string, string>;
  boundaryPolicy?: string;
  resultAvailable?: boolean;
  error?: ServiceError | null;
  columns?: { name: string; type: string }[];
  rows?: unknown[][];
  sql?: string;
  offset?: number;
  returnedRows?: number;
  nextOffset?: number | null;
  availableRows?: number;
  resultTruncated?: boolean;
  durationMs?: number;
};
const ROOT = '/api/v3/dbt';
const POST = 'POST';
// 结果传输的单页上限与查询请求的总行数限制相互独立。
const RESULT_PAGE_SIZE = 200;
const segment = encodeURIComponent;
/** 唯一 v3 传输层，不含构建/部署写入或任何旧协议探测。 */
export function createBuildCatalogApi(fetcher?: Fetcher) {
  const request = createHttpClient(fetcher, ROOT);
  const build = (id: string) => `/builds/${segment(id)}`;
  const resource = (id: string, rid: string) => `${build(id)}/resources/${segment(rid)}`;
  const query = (values: Record<string, string | number | undefined>) =>
    new URLSearchParams(
      Object.entries(values).flatMap(([key, value]) =>
        value === undefined ? [] : [[key, String(value)]],
      ),
    ).toString();
  return {
    projects: (cursor?: string) =>
      request<{ items: DevelopmentProject[]; nextCursor: string | null }>(
        `/projects?${query({ limit: 50, cursor })}`,
      ),
    branches: (projectId: string) =>
      request<BranchList>(`/projects/${segment(projectId)}/branches`),
    builds: (
      filter: { repository?: string; branchName?: string; cursor?: string; limit?: number } = {},
    ) => request<BuildPage>(`/builds?${query({ limit: 50, ...filter })}`),
    build: (id: string) => request<BuildView>(build(id)),
    current: (key: DeploymentKey) =>
      request<DeploymentTarget>(`/deployments/current?${query(key)}`),
    deployments: (key: DeploymentKey, cursor?: string) =>
      request<{ items: DeploymentAttempt[]; nextCursor: string | null }>(
        `/deployments?${query({ ...key, cursor, limit: 50 })}`,
      ),
    catalog: (
      id: string,
      filter: { q?: string; kind?: string; cursor?: string; limit?: number } = {},
    ) =>
      request<{ buildId: string; items: PublishedResource[]; nextCursor: string | null }>(
        `${build(id)}/catalog?${query({ limit: 50, ...filter })}`,
      ),
    resource: (id: string, rid: string) =>
      request<PublishedResource & { buildId: string }>(resource(id, rid)),
    detail: (id: string, rid: string, view: 'lineage' | 'source' | 'native-details') =>
      request<Record<string, unknown>>(`${resource(id, rid)}/${view}`),
    options: (id: string, input: { idempotencyKey: string; metricResourceIds: string[] }) =>
      request<OptionsTask>(`${build(id)}/query-options`, {
        method: POST,
        body: JSON.stringify(input),
      }),
    getOptions: (id: string) => request<OptionsTask>(`/query-options/${segment(id)}`),
    submit: (id: string, input: PublishedQuery) =>
      request<PublishedResult>(`${build(id)}/queries`, {
        method: POST,
        body: JSON.stringify(input),
      }),
    query: (id: string) => request<PublishedResult>(`/queries/${segment(id)}/status`),
    results: (id: string, offset = 0, limit = RESULT_PAGE_SIZE) =>
      request<PublishedResult>(`/queries/${segment(id)}/results?${query({ offset, limit })}`),
  };
}
export const buildCatalogApi = createBuildCatalogApi();
export type BuildCatalogApi = ReturnType<typeof createBuildCatalogApi>;
