export type UUID = string;
export type ProblemDetails = {
  type?: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
};
// dbt 目录和查询只引用服务端已发布的 releaseId，不混用旧语义对象编辑 API。
export type DbtProject = {
  projectId: string;
  observedSha: string | null;
  activeReleaseId: UUID | null;
  lastObservationStatus: string | null;
};
export type DbtRelease = {
  releaseId: UUID;
  projectId: string;
  targetCommitSha: string;
  buildSourceSha: string;
  projectDigest: string;
  state: string;
  cleanupState: string;
  schemaName: string | null;
  errorCode: string | null;
  createdAt: string;
  publishedAt: string | null;
};
export type DbtResource = {
  resourceId: string;
  nativeId: string;
  kind: string;
  name: string;
  description?: string | null;
  sourcePath?: string | null;
  definitionJson: string;
  queryable: boolean;
};
export type DbtCatalog = {
  releaseId: UUID;
  page: number;
  size: number;
  total: number;
  resources: DbtResource[];
};
export type DbtLineage = {
  releaseId: UUID;
  resourceId: string;
  dependencies: { upstreamResourceId: string; downstreamResourceId: string; kind: string }[];
};
export type DbtResourcePresentation = {
  releaseId: UUID;
  resourceId: string;
  kind: string;
  title: string;
  sections: { title: string; fields: Record<string, unknown> }[];
  nativeDetails: Record<string, unknown>;
  queryable: boolean;
};
export type DbtSourceDocument = {
  releaseId: UUID;
  buildSourceSha: string;
  sourcePath: string;
  content: string;
};
export type DbtQueryOptions = {
  releaseId: UUID;
  metrics: { name: string }[];
  dimensions: { token: string; name: string; type?: string }[];
  timeDimensions: { token: string; granularity: string }[];
  allowedFilters: string[];
};
export type DbtQueryFilter = { field: string; operator: string; value: unknown };
export type DbtQueryRequest = {
  releaseId: UUID;
  metrics: string[];
  groupBy?: string[];
  filters?: DbtQueryFilter[];
  limit?: number;
  startTime?: string;
  endTime?: string;
  orderBy?: string[];
};
export type DbtDimensionValuesRequest = {
  releaseId: UUID;
  metrics: string[];
  dimension: string;
  filters?: DbtQueryFilter[];
  limit?: number;
};
export type DbtQuerySubmission = { queryId: UUID; releaseId: UUID; status: 'PENDING' };
export type DbtQueryStatus = {
  queryId: UUID;
  releaseId: UUID;
  targetCommitSha: string;
  mode: 'QUERY' | 'EXPLAIN' | 'PREVIEW' | 'DIMENSION_VALUES';
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  columns: { name: string; type: string }[] | null;
  rows: unknown[][] | null;
  errorCode: string | null;
  truncated: boolean | null;
  durationMs: number | null;
};
