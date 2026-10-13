import { createHttpClient, type Fetcher } from './httpClient';
import type { ExecutionSnapshot } from './environmentApi';

export type ServiceError = {
  code: string;
  message: string;
  retryable: boolean;
  phase?: string;
  buildId?: string;
};
/** 仓库、环境和分支构成 dbt-service 部署自然键。 */
export type DeploymentKey = { repository: string; environmentId: string; branchName: string };
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
export type BuildView = Omit<DeploymentKey, 'environmentId'> & {
  /** 受理时固定的完整执行快照。 */
  execution: ExecutionSnapshot;
  buildId: string;
  requestedCommitSha: string | null;
  commitSha: string | null;
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
export type AvailableDimensionsView = {
  buildId: string;
  metricResourceIds: string[];
  dimensions: PublishedOption[];
};
export type PublishedResult = {
  buildId: string;
  mode?: PublishedQuery['mode'];
  commitSha?: string;
  businessTimezone?: string;
  normalizedTimeRange?: Record<string, string>;
  boundaryPolicy?: string;
  columns?: { name: string; type: string }[];
  rows?: unknown[][];
  sql?: string;
  resultTruncated?: boolean;
};
const ROOT = '/api/dbt';
const POST = 'POST';
const segment = encodeURIComponent;
/** 唯一执行快照 传输层，不含构建/部署写入或任何旧协议探测。 */
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
    builds: (filter: {
      environmentId: string;
      repository?: string;
      buildStatus?: string;
      branchName?: string;
      cursor?: string;
      limit?: number;
    }) => request<BuildPage>(`/builds?${query({ limit: 50, ...filter })}`),
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
    availableDimensions: (id: string, input: { metricResourceIds: string[] }) =>
      request<AvailableDimensionsView>(`${build(id)}/available-dimensions`, {
        method: POST,
        body: JSON.stringify(input),
      }),
    submit: (id: string, input: PublishedQuery) =>
      request<PublishedResult>(`${build(id)}/queries`, {
        method: POST,
        body: JSON.stringify(input),
      }),
  };
}
export const buildCatalogApi = createBuildCatalogApi();
export type BuildCatalogApi = ReturnType<typeof createBuildCatalogApi>;
