export type UUID = string;
export type ProblemDetails = {
  type?: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  /** 服务业务错误码，保留跨 BFF 的错误身份。 */
  code?: string;
  /** 失败所处执行阶段。 */
  phase?: string;
  /** 错误关联的固定构建。 */
  buildId?: string;
  /** 服务声明是否允许重试。 */
  retryable?: boolean;
};
// dbt 目录和查询只引用服务端已发布的 buildId，不混用旧语义对象编辑 API。
export type DbtResource = {
  /** 当前账号具有此资产的使用权限。非授权资源类型省略。 */
  granted?: boolean;
  /** 数据集预览使用的固定构建选择器。 */
  selector?: string;
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
  buildId: UUID;
  page: number;
  size: number;
  total: number;
  resources: DbtResource[];
};
export type DbtLineage = {
  buildId: UUID;
  resourceId: string;
  dependencies: { upstreamResourceId: string; downstreamResourceId: string; kind: string }[];
};
export type DbtResourcePresentation = {
  buildId: UUID;
  resourceId: string;
  kind: string;
  title: string;
  sections: { title: string; fields: Record<string, unknown> }[];
  nativeDetails: Record<string, unknown>;
  queryable: boolean;
};
export type DbtSourceDocument = {
  buildId: UUID;
  buildSourceSha: string;
  sourcePath: string;
  content: string;
};
export type DbtQueryStatus = {
  /** 仅旧版持久查询具有身份；同步查询展示不生成服务查询 ID。 */
  queryId?: UUID;
  buildId: UUID;
  targetCommitSha: string;
  mode: 'QUERY' | 'EXPLAIN' | 'PREVIEW' | 'DIMENSION_VALUES';
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'OUTCOME_UNKNOWN';
  columns: { name: string; type: string }[] | null;
  rows: unknown[][] | null;
  errorCode: string | null;
  /** 服务诊断原文，未知结果不能降级成继续运行。 */
  errorMessage?: string | null;
  truncated: boolean | null;
  durationMs: number | null;
  /** EXPLAIN 完成后生成的 SQL，其他模式或未完成时可为空。 */
  sql?: string | null;
};
