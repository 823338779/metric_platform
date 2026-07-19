import { createHttpClient, type Fetcher } from './httpClient';
import type * as T from './types';

export function createMetricPlatformApi(fetcher?: Fetcher) {
  const request = createHttpClient(fetcher);
  return {
    listDataSources: (includeDeleted = false) =>
      request<T.DataSourceSummary[]>(`/data-sources?includeDeleted=${includeDeleted}`),
    getDataSource: (id: string) => request<T.DataSourceDetail>(`/data-sources/${id}`),
    createDataSource: (body: T.CreateDataSourceRequest) =>
      request<T.DataSourceDetail>('/data-sources', { method: 'POST', body: JSON.stringify(body) }),
    updateDataSource: (id: string, body: T.UpdateDataSourceRequest) =>
      request<T.DataSourceDetail>(`/data-sources/${id}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteDataSource: (id: string) => request<void>(`/data-sources/${id}`, { method: 'DELETE' }),
    validateDataSourceConnection: (body: T.ConnectionSettingsRequest) =>
      request<T.ConnectionTestResponse>('/data-sources:validate-connection', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    testDataSource: (id: string) =>
      request<T.ConnectionTestResponse>(`/data-sources/${id}:test`, { method: 'POST' }),
    activateDataSource: (id: string) =>
      request<T.DataSourceDetail>(`/data-sources/${id}:activate`, { method: 'POST' }),
    deactivateDataSource: (id: string) =>
      request<T.DataSourceDetail>(`/data-sources/${id}:deactivate`, { method: 'POST' }),
    dataSourceDeletionImpact: (id: string) =>
      request<T.DataSourceDeletionImpact>(`/data-sources/${id}/deletion-impact`),
    listCatalogs: (id: string, signal?: AbortSignal) =>
      request<T.SourceCatalog[]>(`/data-sources/${id}/catalogs`, { signal }),
    listSchemas: (id: string, catalogName?: string, signal?: AbortSignal) =>
      request<T.SourceSchema[]>(`/data-sources/${id}/schemas?${sourceParams({ catalogName })}`, {
        signal,
      }),
    listSourceObjects: (
      id: string,
      location: { catalogName?: string; schemaName?: string } = {},
      signal?: AbortSignal,
    ) =>
      request<T.SourceObject[]>(`/data-sources/${id}/objects?${sourceParams(location)}`, {
        signal,
      }),
    listSourceFields: (id: string, location: T.SourceLocation, signal?: AbortSignal) =>
      request<T.SourceColumn[]>(`/data-sources/${id}/fields?${sourceParams(location)}`, { signal }),
    previewSourceObject: (
      id: string,
      location: T.SourceLocation & { limit: 20 | 50 | 100 },
      signal?: AbortSignal,
    ) =>
      request<T.SourcePreview>(`/data-sources/${id}/preview?${sourceParams(location)}`, { signal }),
    listDatasets: (params = new URLSearchParams()) =>
      request<T.PageResponse<T.Dataset>>(`/datasets?${params}`),
    getDataset: (id: string) => request<T.Dataset>(`/datasets/${id}`),
    createDataset: (body: T.CreateDatasetRequest) =>
      request<T.Dataset>('/datasets', { method: 'POST', body: JSON.stringify(body) }),
    updateDataset: (id: string, body: T.UpdateDatasetRequest) =>
      request<T.Dataset>(`/datasets/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    deleteDataset: (id: string, cascade = false) =>
      request<void>(`/datasets/${id}?cascade=${cascade}`, { method: 'DELETE' }),
    deletionImpact: (id: string) => request<T.DeletionImpact>(`/datasets/${id}/deletion-impact`),
    detectFieldChanges: (id: string) =>
      request<T.SchemaDiff>(`/datasets/${id}:detect-field-changes`, { method: 'POST' }),
    syncFields: (id: string, version: number, fingerprint: string) =>
      request<T.SchemaDiff>(`/datasets/${id}:sync-fields`, {
        method: 'POST',
        body: JSON.stringify({ version, fingerprint }),
      }),
    listRelationships: (datasetId?: string) =>
      request<T.DatasetRelationship[]>(
        `/dataset-relationships${datasetId ? `?datasetId=${datasetId}` : ''}`,
      ),
    createRelationship: (body: T.CreateRelationshipRequest) =>
      request<T.DatasetRelationship>('/dataset-relationships', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    revalidateRelationship: (id: string) =>
      request<T.DatasetRelationship>(`/dataset-relationships/${id}:revalidate`, { method: 'POST' }),
    deleteRelationship: (id: string) =>
      request<void>(`/dataset-relationships/${id}`, { method: 'DELETE' }),
    relationshipDeletionImpact: (id: string) =>
      request<T.ResourceDeletionImpact>(`/dataset-relationships/${id}/deletion-impact`),
    listMetrics: (params = new URLSearchParams()) =>
      request<T.PageResponse<T.Metric>>(`/metrics?${params}`),
    createMetric: (body: T.CreateMetricRequest) =>
      request<T.Metric>('/metrics', { method: 'POST', body: JSON.stringify(body) }),
    updateMetric: (id: string, body: T.UpdateMetricRequest) =>
      request<T.Metric>(`/metrics/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    deleteMetric: (id: string) => request<void>(`/metrics/${id}`, { method: 'DELETE' }),
    metricDeletionImpact: (id: string) =>
      request<T.ResourceDeletionImpact>(`/metrics/${id}/deletion-impact`),
    listDimensions: (params = new URLSearchParams()) =>
      request<T.PageResponse<T.Dimension>>(`/dimensions?${params}`),
    createDimension: (body: T.CreateDimensionRequest) =>
      request<T.Dimension>('/dimensions', { method: 'POST', body: JSON.stringify(body) }),
    updateDimension: (id: string, body: T.UpdateDimensionRequest) =>
      request<T.Dimension>(`/dimensions/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    deleteDimension: (id: string) => request<void>(`/dimensions/${id}`, { method: 'DELETE' }),
    dimensionDeletionImpact: (id: string) =>
      request<T.ResourceDeletionImpact>(`/dimensions/${id}/deletion-impact`),
    getCapabilities: () => request<T.CapabilitiesResponse>('/capabilities'),
    getQueryConnectivity: () => request<T.QueryConnectivityResponse>('/query-connectivity'),
    searchSemantic: (query: string) =>
      request<T.SemanticSearchResult[]>('/semantic-objects:search', {
        method: 'POST',
        body: JSON.stringify({ query }),
      }),
    querySemanticObjects: (body: T.SemanticObjectQueryRequest) =>
      request<T.SemanticObjectQueryResponse>('/semantic-objects:query', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    resolveSemantic: (body: T.SemanticResolveRequest) =>
      request<T.SemanticResolveResponse>('/semantic-objects:resolve', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    searchDimensionMembers: (dimensionId: string, body: T.DimensionMemberSearchRequest) =>
      request<T.DimensionMemberSearchResponse>(`/dimensions/${dimensionId}/members:search`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    queryDimensionMembers: (dimensionId: string, body: T.DimensionMemberQueryRequest) =>
      request<T.DimensionMemberQueryResponse>(`/dimensions/${dimensionId}/members:query`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    validateQuery: (body: T.QueryRequest) =>
      request<T.ValidationResponse>('/queries:validate', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    executeQuery: (body: T.QueryRequest) =>
      request<T.QueryResponse>('/queries', { method: 'POST', body: JSON.stringify(body) }),
  };
}
export const metricPlatformApi = createMetricPlatformApi();
export type MetricPlatformApi = ReturnType<typeof createMetricPlatformApi>;

function sourceParams(values: Record<string, string | number | undefined>) {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value));
  });
  return params;
}
