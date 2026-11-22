import { createHttpClient, type Fetcher } from './httpClient';
import type { BuildExecutionSnapshot } from './environmentApi';

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
/** 构建执行事实；查询能力由执行请求的实际结果确定。 */
export type BuildView = Omit<DeploymentKey, 'environmentId'> & {
  /** 受理时固定的完整执行快照。 */
  execution: BuildExecutionSnapshot;
  buildId: string;
  requestedCommitSha: string | null;
  commitSha: string | null;
  toolchainVersion: string;
  buildStatus: string;
  phase: string;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
  catalogAvailable: boolean;
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
  /** 当前账号可在数据查询中使用该维度。 */
  granted?: boolean;
  optionId: string;
  /** 原生维度语义类型，不推断物理列类型。 */
  dimensionType: string;
  name?: string;
  semanticModelName?: string;
  displayName: string;
  granularities: string[];
  operators: string[];
};
/** 固定构建查询按模式约束字段，避免发送上游拒绝的无关空字段。 */
type MetricSelection = {
  metrics: string[];
  filters?: { optionId: string; operator: string; value: unknown }[];
  startTime?: string;
  endTime?: string;
  limit?: number;
};
export type PublishedQuery =
  | (MetricSelection & {
      mode: 'QUERY' | 'EXPLAIN';
      groupBy?: { optionId: string; grain?: string }[];
      orderBy?: { fieldId: string; direction: 'ASC' | 'DESC' }[];
    })
  | (MetricSelection & { mode: 'DIMENSION_VALUES'; dimensionOptionId: string })
  | { mode: 'PREVIEW'; selector: string; limit?: number };
/** 原生 Manifest 节点保持扩展字段，展示层只读取明确支持的字段。 */
export type NativeNode = Record<string, unknown> & {
  unique_id?: string;
  resource_type?: string;
  name?: string;
  label?: string;
  description?: string;
  original_file_path?: string;
  config?: { materialized?: string };
  depends_on?: { nodes?: string[] };
};
export type NativeCatalog = (NativeNode & { unique_id: string })[];
export type ResourceView = {
  buildId: string;
  resourceId: string;
  resource: NativeNode;
  source: { path: string; content: string } | null;
};
export type AvailableDimensionsView = {
  buildId: string;
  metrics: string[];
  dimensions: PublishedOption[];
};
type QueryIdentity = { buildId: string; commitSha: string | null };
type TimeContext = {
  businessTimezone: string;
  normalizedTimeRange: { startTime: string | null; endTime: string | null };
};
type TabularResult = {
  columns: { name: string; type: string }[];
  rows: unknown[][];
  resultTruncated: boolean;
  sql?: never;
};
/** EXPLAIN 仅有 SQL，PREVIEW 不带指标时间语义。 */
export type PublishedResult = QueryIdentity &
  (
    | (TabularResult & TimeContext & { mode: 'QUERY' | 'DIMENSION_VALUES' })
    | (TabularResult & { mode: 'PREVIEW'; businessTimezone?: never; normalizedTimeRange?: never })
    | (TimeContext & {
        mode: 'EXPLAIN';
        sql: string;
        columns?: never;
        rows?: never;
        resultTruncated?: never;
      })
  );
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
    current: (key: DeploymentKey, refreshSource = false) =>
      request<DeploymentTarget>(
        `/deployments/current?${query({ ...key, refreshSource: String(refreshSource) })}`,
      ),
    deployments: (key: DeploymentKey, cursor?: string) =>
      request<{ items: DeploymentAttempt[]; nextCursor: string | null }>(
        `/deployments?${query({ ...key, cursor, limit: 50 })}`,
      ),
    catalog: (id: string) => request<NativeCatalog>(`${build(id)}/catalog`),
    resource: (id: string, rid: string) => request<ResourceView>(resource(id, rid)),
    lineage: (id: string, selector: string) =>
      request<NativeNode[]>(`${build(id)}/lineage?${query({ selector })}`),
    availableDimensions: (id: string, input: { metrics: string[] }) =>
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
