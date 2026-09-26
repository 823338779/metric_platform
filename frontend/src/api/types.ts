export type UUID = string;
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
export type PageResponse<T> = { items: T[]; total: number; page: number; size: number };
export type DatabaseType = 'STARROCKS' | 'MYSQL' | 'POSTGRESQL';
export type DataSourceStatus = 'ACTIVE' | 'INACTIVE' | 'DELETED';
export type HealthStatus = 'UNKNOWN' | 'HEALTHY' | 'UNHEALTHY';
export type DataSourceSummary = {
  id: UUID;
  name: string;
  description?: string;
  databaseType: DatabaseType;
  databaseName?: string;
  status: DataSourceStatus;
  healthStatus: HealthStatus;
  lastCheckedAt?: string;
  lastLatencyMs?: number;
};
export type DataSourceDetail = DataSourceSummary & {
  jdbcUrl: string;
  username: string;
  password: string;
  defaultCatalog?: string;
  defaultSchema?: string;
  version: number;
};
export type ConnectionSettingsRequest = {
  databaseType: DatabaseType;
  jdbcUrl: string;
  username: string;
  password: string;
  defaultCatalog?: string;
  defaultSchema?: string;
};
export type CreateDataSourceRequest = ConnectionSettingsRequest & {
  name: string;
  description?: string;
};
export type UpdateDataSourceRequest = {
  name: string;
  description?: string;
  databaseType: DatabaseType;
  jdbcUrl?: string;
  username: string;
  password: string;
  defaultCatalog?: string;
  defaultSchema?: string;
  version: number;
};
export type ConnectionTestResponse = { successful: boolean; latencyMs: number; message: string };
export type DataSourceDeletionImpact = { datasets: { id: UUID; name: string }[] };
export type SourceCatalog = { name: string };
export type SourceSchema = { catalogName?: string; schemaName: string };
export type SourceObject = {
  catalogName?: string;
  schemaName?: string;
  objectName: string;
  objectType: 'TABLE' | 'VIEW';
};
export type SourceColumn = {
  physicalName: string;
  physicalType: string;
  jdbcType: number;
  ordinal: number;
  nullable: boolean;
};
export type SourceLocation = { catalogName?: string; schemaName?: string; objectName: string };
export type SourcePreview = {
  columns: { name: string; physicalType: string; jdbcType: number }[];
  rows: unknown[][];
  limit: 20 | 50 | 100;
};
export type DatasetField = {
  id: UUID;
  physicalName: string;
  displayName: string;
  physicalType: string;
  selected: boolean;
  version: number;
  description?: string;
  sensitivityLevel?: string;
};
export type FilterOperator =
  | 'EQ'
  | 'NE'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'
  | 'BETWEEN'
  | 'IN'
  | 'CONTAINS'
  | 'IS_NULL'
  | 'IS_NOT_NULL';
export type FilterExpression = {
  logicalOperator?: 'AND' | 'OR';
  children?: FilterExpression[];
  fieldId?: UUID;
  operator?: FilterOperator;
  values?: unknown[];
};
export type Dataset = {
  id: UUID;
  dataSourceId: UUID;
  name: string;
  description?: string;
  schemaName: string;
  objectName: string;
  objectType: 'TABLE' | 'VIEW';
  filterExpression?: FilterExpression;
  version: number;
  fields: DatasetField[];
  aliases: string[];
  businessGrain?: string;
  defaultTimeDimensionId?: UUID;
  freshnessPolicy?: string;
  qualityStatus: string;
  domain?: string;
  owner?: string;
  status: string;
  certificationStatus: string;
};
export type FieldPair = { sourceFieldId: UUID; targetFieldId: UUID };
export type DatasetRelationship = {
  id: UUID;
  name: string;
  sourceDatasetId: UUID;
  targetDatasetId: UUID;
  cardinality: 'ONE_TO_ONE' | 'MANY_TO_ONE';
  joinType: 'LEFT';
  fieldPairs: FieldPair[];
  validationStatus: 'VALID' | 'INVALID' | 'UNKNOWN';
  lastValidatedAt?: string;
  version: number;
  description?: string;
  displayPath?: string;
  semanticRole?: string;
  fanoutSafe: boolean;
  status: string;
};
export type Metric = {
  id: UUID;
  datasetId: UUID;
  datasetFieldId?: UUID;
  name: string;
  displayName: string;
  aggregation: 'COUNT' | 'COUNT_DISTINCT' | 'SUM' | 'AVG' | 'MIN' | 'MAX';
  version: number;
  description?: string;
  businessDefinition?: string;
  aliases: string[];
  unit?: string;
  formatPattern?: string;
  status: string;
  domain?: string;
  owner?: string;
  certificationStatus: string;
  aggregationBehavior?: string;
  additivity?: string;
  metricType: string;
  defaultTimeDimensionId?: UUID;
};
export type TimeGranularity = 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR';
export type Dimension = {
  id: UUID;
  datasetId: UUID;
  datasetFieldId: UUID;
  name: string;
  displayName: string;
  type: 'STANDARD' | 'TIME';
  allowedGranularities: TimeGranularity[];
  version: number;
  description?: string;
  businessDefinition?: string;
  aliases: string[];
  domain?: string;
  owner?: string;
  status: string;
  certificationStatus: string;
  semanticType?: string;
  valueType?: string;
  defaultGranularity?: TimeGranularity;
  sensitivityLevel: string;
};
export type SchemaDiff = {
  added: FieldChange[];
  removed: FieldChange[];
  typeChanged: FieldChange[];
  blockingImpacts: string[];
  fingerprint: string;
};
export type FieldChange = {
  fieldId?: UUID;
  physicalName: string;
  previousType?: string;
  currentType?: string;
};
export type DeletionImpact = {
  fieldCount: number;
  metricCount: number;
  dimensionCount: number;
  relationshipCount: number;
};
export type DimensionSelection = { dimensionId: UUID; granularity?: TimeGranularity };
export type DimensionFilter = { dimensionId: UUID; operator: FilterOperator; values?: unknown[] };
export type TimePreset =
  | 'TODAY'
  | 'YESTERDAY'
  | 'LAST_7_DAYS'
  | 'LAST_30_DAYS'
  | 'MONTH_TO_DATE'
  | 'QUARTER_TO_DATE'
  | 'YEAR_TO_DATE'
  | 'LAST_MONTH'
  | 'LAST_QUARTER'
  | 'LAST_YEAR';
export type TimeRange = {
  dimensionId: UUID;
  start?: string;
  end?: string;
  preset?: TimePreset;
  timezone: string;
};
export type SortSelection = {
  type: 'METRIC' | 'DIMENSION';
  objectId: UUID;
  direction: 'ASC' | 'DESC';
};
export type QueryRequest = {
  metricIds: UUID[];
  dimensions: DimensionSelection[];
  relationshipIds: UUID[];
  filters?: DimensionFilter[];
  timeRange?: TimeRange;
  sort?: SortSelection[];
  limit?: number;
};
export type SemanticSelection = { id: UUID; datasetId: UUID; name: string };
export type AggregatePlanSummary = {
  datasetId: UUID;
  metricIds: UUID[];
  dimensionIds: UUID[];
  relationshipIds: UUID[];
};
export type ValidationResponse = {
  valid: boolean;
  metrics: SemanticSelection[];
  dimensions: SemanticSelection[];
  aggregates: AggregatePlanSummary[];
  resolvedRelationshipIds: UUID[];
  parameterCount: number;
  issues: ValidationIssue[];
  warnings: string[];
  normalizedRequest?: QueryRequest;
  catalogVersion?: string;
  planToken?: string;
};
export type ValidationIssue = {
  code: string;
  message: string;
  objectType?: 'DATASET' | 'METRIC' | 'DIMENSION';
  objectId?: UUID;
  candidatePaths?: RelationshipPathSummary[];
};
export type QueryResponse = {
  columns: ResultColumn[];
  rows: unknown[][];
  metrics: Metric[];
  dimensions: Dimension[];
  sql?: string;
  durationMs: number;
  queryId: UUID;
  rowCount: number;
  truncated: boolean;
  appliedFilters: DimensionFilter[];
  appliedTimeRange?: TimeRange;
  freshnessWarnings: string[];
  catalogVersion: string;
  evidence: QueryEvidence;
};
export type ResultColumn = {
  name: string;
  type: string;
  semanticType?: 'METRIC' | 'DIMENSION';
  semanticObjectId?: UUID;
  displayName?: string;
  logicalType?: string;
  unit?: string;
  formatPattern?: string;
  granularity?: TimeGranularity;
};
export type QueryEvidence = {
  queryId: UUID;
  catalogVersion: string;
  definitionVersions: Record<UUID, number>;
  relationshipIds: UUID[];
};
export type SemanticSearchResult = {
  type: 'DATASET' | 'METRIC' | 'DIMENSION';
  id: UUID;
  datasetId: UUID;
  name: string;
  displayName: string;
  description?: string;
  aliases: string[];
  score: number;
  matchReasons: string[];
  status?: string;
  certificationStatus?: string;
  domain?: string;
  version: number;
  updatedAt?: string;
  summary: Record<string, unknown>;
};
export type SemanticSearchRequest = {
  query: string;
  datasetIds?: UUID[];
  objectTypes?: ('DATASET' | 'METRIC' | 'DIMENSION')[];
  domains?: string[];
  statuses?: string[];
  certifiedOnly?: boolean;
  topK?: number;
};
export type SemanticObjectSort = 'RELEVANCE_DESC' | 'NAME_ASC' | 'UPDATED_AT_DESC';
export type SemanticObjectQueryRequest = {
  text?: string;
  types?: ('DATASET' | 'METRIC' | 'DIMENSION')[];
  datasetIds?: UUID[];
  domains?: string[];
  statuses?: string[];
  certifiedOnly?: boolean;
  sort?: SemanticObjectSort;
  limit?: number;
  cursor?: string;
};
export type PageInfo = { nextCursor?: string; hasMore: boolean; limit: number };
export type SemanticObjectFacets = {
  types: Partial<Record<'DATASET' | 'METRIC' | 'DIMENSION', number>>;
  domains: Record<string, number>;
  statuses: Record<string, number>;
};
export type SemanticObjectQueryResponse = {
  items: SemanticSearchResult[];
  pageInfo: PageInfo;
  facets: SemanticObjectFacets;
  catalogVersion: string;
};
export type RelationshipPathSummary = {
  relationshipId: UUID;
  name: string;
  displayPath?: string;
  sourceDatasetId: UUID;
  targetDatasetId: UUID;
  cardinality: string;
  semanticRole?: string;
  fanoutSafe: boolean;
};
export type SemanticResolveRequest = {
  objects: { type: 'DATASET' | 'METRIC' | 'DIMENSION'; id: UUID }[];
  relationshipIds?: UUID[];
};
export type SemanticResolveResponse = {
  catalogVersion: string;
  objects: ResolvedSemanticObject[];
  relationshipPaths: RelationshipPathSummary[];
};
export type ResolvedSemanticObject = {
  type: 'DATASET' | 'METRIC' | 'DIMENSION';
  id: UUID;
  datasetId: UUID;
  name: string;
  displayName?: string;
  description?: string;
  businessDefinition?: string;
  aliases: string[];
  domain?: string;
  owner?: string;
  status?: string;
  certificationStatus?: string;
  datasetGrain?: string;
  unit?: string;
  formatPattern?: string;
  valueType?: string;
  allowedGranularities: TimeGranularity[];
  freshnessPolicy?: string;
  qualityStatus?: string;
  version: number;
};
export type CapabilitiesResponse = {
  apiVersion: string;
  catalogVersion: string;
  queryFeatures: string[];
  limits: Record<string, number>;
  timePresets: TimePreset[];
  planTokenSupported: boolean;
};
export type ConnectivityPathStatus = 'UNIQUE_PATH' | 'AMBIGUOUS_PATH';
export type DatasetConnectivity = { datasetId: UUID; dataSourceId: UUID };
export type ConnectivityPath = {
  sourceDatasetId: UUID;
  targetDatasetId: UUID;
  status: ConnectivityPathStatus;
  relationshipIds: UUID[];
};
export type QueryConnectivityResponse = {
  catalogVersion: string;
  datasets: DatasetConnectivity[];
  paths: ConnectivityPath[];
};
export type DimensionMemberSearchRequest = {
  query: string;
  mode?: 'AUTO' | 'EXACT' | 'PREFIX' | 'CONTAINS';
  topK?: number;
};
export type DimensionMemberSearchResponse = {
  dimensionId: UUID;
  members: { value: string; displayValue: string }[];
  truncated: boolean;
};
export type DimensionMemberQueryRequest = {
  text?: string;
  mode?: 'AUTO' | 'EXACT' | 'PREFIX' | 'CONTAINS';
  limit?: number;
  cursor?: string;
};
export type DimensionMemberQueryResponse = {
  dimensionId: UUID;
  items: { value: string; displayValue: string }[];
  pageInfo: PageInfo;
  memberVersion: string;
};
export type ResourceDeletionImpact = {
  resourceId: UUID;
  deletable: boolean;
  dependencies: { type: string; id: UUID; name: string; reason: string }[];
  warnings: string[];
};
export type ProblemDetails = {
  type?: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  blockingImpacts?: string[];
};

export type CreateDatasetRequest = {
  dataSourceId: UUID;
  name: string;
  description?: string;
  schemaName: string;
  objectName: string;
  selectedPhysicalFields: string[];
  aliases?: string[];
};
export type UpdateDatasetRequest = {
  name: string;
  description?: string;
  filterExpression?: FilterExpression;
  fieldUpdates?: { fieldId: UUID; displayName: string; selected: boolean }[];
  version: number;
  aliases?: string[];
};
export type CreateMetricRequest = Pick<
  Metric,
  'datasetId' | 'datasetFieldId' | 'name' | 'displayName' | 'aggregation'
> &
  Partial<
    Omit<
      Metric,
      'id' | 'version' | 'datasetId' | 'datasetFieldId' | 'name' | 'displayName' | 'aggregation'
    >
  >;
export type UpdateMetricRequest = Omit<CreateMetricRequest, 'datasetId'> & { version: number };
export type CreateDimensionRequest = Pick<
  Dimension,
  'datasetId' | 'datasetFieldId' | 'name' | 'displayName' | 'type' | 'allowedGranularities'
> &
  Partial<
    Omit<
      Dimension,
      | 'id'
      | 'version'
      | 'datasetId'
      | 'datasetFieldId'
      | 'name'
      | 'displayName'
      | 'type'
      | 'allowedGranularities'
    >
  >;
export type UpdateDimensionRequest = Omit<CreateDimensionRequest, 'datasetId'> & {
  version: number;
};
export type CreateRelationshipRequest = Pick<
  DatasetRelationship,
  'name' | 'sourceDatasetId' | 'targetDatasetId' | 'cardinality' | 'fieldPairs'
>;
