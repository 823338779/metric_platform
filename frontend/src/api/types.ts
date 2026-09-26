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
export type DbtQueryRequest = {
  releaseId: UUID;
  metrics: string[];
  groupBy?: string[];
  filters?: { field: string; operator: string; value: unknown }[];
  limit?: number;
};
export type DbtQuerySubmission = { queryId: UUID; releaseId: UUID; status: 'PENDING' };
export type DbtQueryStatus = {
  queryId: UUID;
  releaseId: UUID;
  targetCommitSha: string;
  mode: 'QUERY' | 'EXPLAIN' | 'PREVIEW';
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  columns: { name: string; type: string }[] | null;
  rows: unknown[][] | null;
  errorCode: string | null;
  truncated: boolean | null;
  durationMs: number | null;
};
