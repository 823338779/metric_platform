import { createHttpClient, type Fetcher } from './httpClient';

export type Publication = {
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
  return {
    projects: () => request<PublishedProject[]>(ROOT),
    publication: (id: string) => request<PublishedProject>(`${project(id)}/publication`),
    releases: (id: string) => request<Publication[]>(`${project(id)}/releases`),
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
}
export const publishedCatalogApi = createPublishedCatalogApi();
export type PublishedCatalogApi = ReturnType<typeof createPublishedCatalogApi>;
