import { createHttpClient, type Fetcher } from './httpClient';
import type * as T from './types';

export function createMetricPlatformApi(fetcher?: Fetcher) {
  const request = createHttpClient(fetcher);
  return {
    // 新 dbt API 固定项目及版本，定义仅由 Git main 发布。
    listDbtProjects: () => request<T.DbtProject[]>('/semantic-projects'),
    listDbtReleases: (projectId: string) =>
      request<T.DbtRelease[]>(`/semantic-projects/${encodeURIComponent(projectId)}/releases`),
    getDbtCatalog: (projectId: string, releaseId?: string, q = '') =>
      request<T.DbtCatalog>(
        `/semantic-projects/${encodeURIComponent(projectId)}/catalog?${new URLSearchParams({ ...(releaseId ? { releaseId } : {}), q })}`,
      ),
    getDbtResource: (projectId: string, resourceId: string, releaseId: string) =>
      request<T.DbtResource>(
        `/semantic-projects/${encodeURIComponent(projectId)}/resources/${encodeURIComponent(resourceId)}?releaseId=${encodeURIComponent(releaseId)}`,
      ),
    getDbtLineage: (projectId: string, resourceId: string, releaseId: string) =>
      request<T.DbtLineage>(
        `/semantic-projects/${encodeURIComponent(projectId)}/resources/${encodeURIComponent(resourceId)}/lineage?releaseId=${encodeURIComponent(releaseId)}`,
      ),
    submitDbtQuery: (projectId: string, body: T.DbtQueryRequest) =>
      request<T.DbtQuerySubmission>(`/semantic-projects/${encodeURIComponent(projectId)}/queries`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    explainDbtQuery: (projectId: string, body: T.DbtQueryRequest) =>
      request<T.DbtQuerySubmission>(
        `/semantic-projects/${encodeURIComponent(projectId)}/queries:explain`,
        { method: 'POST', body: JSON.stringify(body) },
      ),
    previewDbtDataset: (projectId: string, resourceId: string, releaseId: string, limit: number) =>
      request<T.DbtQuerySubmission>(
        `/semantic-projects/${encodeURIComponent(projectId)}/datasets/${encodeURIComponent(resourceId)}:preview`,
        { method: 'POST', body: JSON.stringify({ releaseId, limit }) },
      ),
    getDbtQuery: (projectId: string, queryId: string) =>
      request<T.DbtQueryStatus>(
        `/semantic-projects/${encodeURIComponent(projectId)}/queries/${encodeURIComponent(queryId)}`,
      ),
  };
}
export const metricPlatformApi = createMetricPlatformApi();
export type MetricPlatformApi = ReturnType<typeof createMetricPlatformApi>;
