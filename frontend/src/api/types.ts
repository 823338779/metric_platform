export type UUID = string;
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
};
export type Metric = {
  id: UUID;
  datasetId: UUID;
  datasetFieldId?: UUID;
  name: string;
  displayName: string;
  aggregation: 'COUNT' | 'COUNT_DISTINCT' | 'SUM' | 'AVG' | 'MIN' | 'MAX';
  version: number;
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
export type QueryRequest = {
  metricIds: UUID[];
  dimensions: DimensionSelection[];
  relationshipIds: UUID[];
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
};
export type QueryResponse = {
  columns: { name: string; type: string }[];
  rows: unknown[][];
  metrics: Metric[];
  dimensions: Dimension[];
};
export type SemanticSearchResult = {
  type: 'DATASET' | 'METRIC' | 'DIMENSION';
  id: UUID;
  datasetId: UUID;
  name: string;
  displayName: string;
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
};
export type UpdateDatasetRequest = {
  name: string;
  description?: string;
  filterExpression?: FilterExpression;
  fieldUpdates?: { fieldId: UUID; displayName: string; selected: boolean }[];
  version: number;
};
export type CreateMetricRequest = Omit<Metric, 'id' | 'version'>;
export type UpdateMetricRequest = Omit<CreateMetricRequest, 'datasetId'> & { version: number };
export type CreateDimensionRequest = Omit<Dimension, 'id' | 'version'>;
export type UpdateDimensionRequest = Omit<CreateDimensionRequest, 'datasetId'> & {
  version: number;
};
export type CreateRelationshipRequest = Pick<
  DatasetRelationship,
  'name' | 'sourceDatasetId' | 'targetDatasetId' | 'cardinality' | 'fieldPairs'
>;
