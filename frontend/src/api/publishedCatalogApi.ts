import { createHttpClient, type Fetcher } from './httpClient';

export type Publication = {
  /** 分支路由返回的固定归属；旧生产协议可省略。 */
  branchId?: string;
  /** 服务过滤后的构建检查，不包含连接配置或原始 stderr。 */
  validationSummary?: {
    /** 执行器提供的失败阶段与脱敏检查消息。 */
    phase?: string;
    checks: { name: string; status: string; message?: string | null }[];
    truncated?: boolean;
  } | null;
  projectId: string;
  releaseId: string;
  runId: string | null;
  artifactSetId: string | null;
  publicationSequence: number;
  publishedAt: string | null;
  sourceSha: string;
  buildMode: 'FULL_BUILD' | 'SEMANTIC_ONLY' | 'SELECTIVE_BUILD' | null;
  catalogDigest: string | null;
  createdAt: string;
  catalogSchemaVersion: number;
  state: string;
  errorCode?: string | null;
};
export type PublishedProject = { projectId: string; activePublication: Publication | null };
/** 分支实例独立于 ref 名称；删除后同名新分支使用新的 branchId。 */
export type BranchView = {
  projectId: string;
  branchId: string;
  gitRef: string;
  mode: 'PRODUCTION' | 'PREVIEW';
  status: 'PROVISIONING' | 'ACTIVE' | 'DELETING' | 'DELETED' | 'FAILED';
  baseCommitSha: string | null;
  baseReleaseId: string | null;
  observedHeadSha: string | null;
  activeReleaseId: string | null;
  latestReleaseId: string | null;
  version: number;
  mergeRequestUrl: string | null;
};
/** 每次页面读取或查询持有完整上下文；releaseId 为空表示预览尚未就绪。 */
export type BranchContext = { projectId: string; branchId: string; releaseId: string | null };
export type BranchDiff = {
  branchId: string;
  releaseId: string;
  baseCommitSha: string | null;
  targetCommitSha: string;
  productionAdvanced: boolean;
  resourceBaselineAvailable: boolean;
  truncated: boolean;
  files: { path: string; status: string; diff: string; truncated?: boolean }[];
  resources: { nativeId: string; kind: string; status: string }[];
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
export type PublishedPage = {
  releaseId: string;
  page: number;
  size: number;
  total: number;
  resources: PublishedResource[];
};
export type PublishedOption = {
  optionId: string;
  resourceId: string | null;
  displayName: string;
  granularities: string[];
  operators: string[];
};
export type PublishedQuery = {
  releaseId: string;
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
export type PublishedResult = {
  queryId: string;
  projectId: string;
  releaseId: string;
  state: string;
  columns?: { name: string; type: string }[];
  rows?: unknown[][];
  sql?: string;
  errorCode?: string;
  truncated?: boolean;
  /** 运行服务返回的查询用途，用于区分 SQL 解释与取数结果。 */
  mode?: PublishedQuery['mode'];
  /** 本次查询固定的源码版本；服务未返回时不推测。 */
  targetCommitSha?: string;
  /** 运行服务报告的耗时（毫秒）。 */
  durationMs?: number;
};

const ROOT = '/semantic-projects';
const POST = 'POST';
const segment = encodeURIComponent;
export function createPublishedCatalogApi(fetcher?: Fetcher) {
  const request = createHttpClient(fetcher, '/api/v2');
  const project = (id: string) => `${ROOT}/${segment(id)}`;
  const release = (id: string, version: string) => `${project(id)}/releases/${segment(version)}`;
  const branch = (id: string, branchId: string) => `${project(id)}/branches/${segment(branchId)}`;
  return {
    branches: (id: string) => request<BranchView[]>(`${project(id)}/branches`),
    branch: (id: string, branchId: string) => request<BranchView>(branch(id, branchId)),
    diff: (context: BranchContext) => {
      if (!context.releaseId) return Promise.reject(new Error('预览尚未就绪'));
      return scoped<BranchDiff>(
        `${branch(context.projectId, context.branchId)}/releases/${segment(context.releaseId)}/diff`,
        context,
      );
    },
    /** 为既有目录适配器提供固定上下文，避免在每个资源回调中读取当前 UI 选择。 */
    inBranch: (context: BranchContext) => {
      const pinned = { ...context };
      const root = branch(pinned.projectId, pinned.branchId);
      const path = (id: string, version?: string) => {
        if (id !== pinned.projectId || (version !== undefined && version !== pinned.releaseId))
          throw new Error('请求归属与固定上下文不一致');
        return version === undefined ? root : `${root}/releases/${segment(version)}`;
      };
      return {
        publication: (id: string) => scoped<PublishedProject>(`${path(id)}/publication`, pinned),
        releases: (id: string) =>
          request<Publication[]>(`${path(id)}/releases`).then((values) => {
            if (values.some((value) => value.branchId !== pinned.branchId))
              throw new Error('响应归属与分支不一致');
            return values;
          }),
        release: (id: string, version: string) =>
          scoped<Publication>(`${path(id, version)}`, pinned, undefined, version),
        catalog: (id: string, version: string, q = '', kind = '', page = 1) =>
          scoped<PublishedPage>(
            `${path(id, version)}/catalog?${new URLSearchParams({ q, page: String(page), size: '50', ...(kind ? { kind } : {}) })}`,
            pinned,
            undefined,
            version,
          ),
        resource: (id: string, version: string, resourceId: string) =>
          scoped<PublishedResource & { releaseId: string }>(
            `${path(id, version)}/resources/${segment(resourceId)}`,
            pinned,
            undefined,
            version,
          ),
        detail: (
          id: string,
          version: string,
          resourceId: string,
          view: 'lineage' | 'source' | 'native-details',
        ) =>
          scoped<Record<string, unknown>>(
            `${path(id, version)}/resources/${segment(resourceId)}/${view}`,
            pinned,
            undefined,
            version,
          ),
        options: (id: string, version: string, metrics: string[]) => {
          path(id, version);
          return scoped<{ releaseId: string; options: PublishedOption[] }>(
            `${root}/query-options`,
            pinned,
            {
              method: POST,
              body: JSON.stringify({ releaseId: version, metricResourceIds: metrics }),
            },
            version,
          );
        },
        submit: (id: string, body: PublishedQuery) => {
          path(id, body.releaseId);
          return scoped<PublishedResult>(
            `${root}/queries`,
            pinned,
            { method: POST, body: JSON.stringify(body) },
            body.releaseId,
          );
        },
        query: (id: string, queryId: string) =>
          scoped<PublishedResult>(
            `${path(id)}/queries/${segment(queryId)}`,
            pinned,
            undefined,
            pinned.releaseId ?? undefined,
          ),
      };
    },
    projects: () => request<PublishedProject[]>(ROOT),
    publication: (id: string) => request<PublishedProject>(`${project(id)}/publication`),
    releases: (id: string) => request<Publication[]>(`${project(id)}/releases`),
    release: (id: string, version: string) => request<Publication>(release(id, version)),
    catalog: (id: string, version: string, q = '', kind = '', page = 1) =>
      request<PublishedPage>(
        `${release(id, version)}/catalog?${new URLSearchParams({ q, page: String(page), size: '50', ...(kind ? { kind } : {}) })}`,
      ),
    resource: (id: string, version: string, resourceId: string) =>
      request<PublishedResource & { releaseId: string }>(
        `${release(id, version)}/resources/${segment(resourceId)}`,
      ),
    detail: (
      id: string,
      version: string,
      resourceId: string,
      view: 'lineage' | 'source' | 'native-details',
    ) =>
      request<Record<string, unknown>>(
        `${release(id, version)}/resources/${segment(resourceId)}/${view}`,
      ),
    options: (id: string, version: string, metrics: string[]) =>
      request<{ releaseId: string; options: PublishedOption[] }>(`${project(id)}/query-options`, {
        method: POST,
        body: JSON.stringify({ releaseId: version, metricResourceIds: metrics }),
      }),
    submit: (id: string, body: PublishedQuery) =>
      request<PublishedResult>(`${project(id)}/queries`, {
        method: POST,
        body: JSON.stringify(body),
      }),
    query: (id: string, queryId: string) =>
      request<PublishedResult>(`${project(id)}/queries/${segment(queryId)}`),
  };

  // 即使上游错误返回 200，也不能把另一分支/版本的资源绘制到当前页面。
  async function scoped<T>(
    path: string,
    context: BranchContext,
    init?: RequestInit,
    version?: string,
  ): Promise<T> {
    const value = await request<T & { branchId?: string; releaseId?: string }>(path, init);
    if (
      value.branchId !== context.branchId ||
      (version !== undefined && value.releaseId !== version)
    )
      throw new Error('响应归属与固定上下文不一致');
    return value;
  }
}
export const publishedCatalogApi = createPublishedCatalogApi();
export type PublishedCatalogApi = ReturnType<typeof createPublishedCatalogApi>;
