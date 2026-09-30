import type { MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtLineage, DbtResourcePresentation } from '../../api/types';
import { hasSerializedNode, restoreArtifactPresentation } from './resourcePresentation';

export type DataTypeApi = Pick<MetricPlatformApi, 'getDbtResource' | 'getDbtPresentation'>;
export type ResourceDataType = {
  /** 可显示的数据类型；仅明确声明或直接列映射保留数据库精度。 */
  label: string;
  /** 类型依据、源字段或尚不能确定结果精度的原因。 */
  description: string;
};
const METRIC = 'METRIC';
const SEMANTIC_MODEL = 'SEMANTIC_MODEL';
const PHYSICAL_KINDS = new Set(['TABLE', 'VIEW', 'MODEL']);
const COUNT_AGGREGATIONS = new Set(['count', 'count_distinct']);
const NUMERIC_AGGREGATIONS = new Set([
  'sum',
  'average',
  'avg',
  'median',
  'percentile',
  'sum_boolean',
]);
const PRESERVING_AGGREGATIONS = new Set(['min', 'max']);
const RATIO = 'ratio';
const STRING = 'string';
const OBJECT = 'object';
const COLUMN_TYPE = 'type';
const DATA_TYPE_KEYS = ['data_type', 'dataType'];
const IDENTIFIER = /^(?:[A-Za-z_][\w$]*|"[A-Za-z_][\w$]*"|`[A-Za-z_][\w$]*`)$/;
const QUOTES = /["`]/g;
const INVALID_METADATA = '类型元数据与请求的资源或版本不一致';
const UNKNOWN = {
  label: '待确认',
  description: '该版本未提供明确的数据类型，无法从现有定义可靠确定。',
};
const NUMERIC = {
  label: 'NUMBER · 数值',
  description: '由聚合或比率计算确定为数值；结果精度以引擎返回的类型为准。',
};
const INTEGER = {
  label: 'INTEGER · 整数',
  description: '由计数聚合确定为整数；具体位宽以引擎返回的类型为准。',
};

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === OBJECT && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
function text(value: unknown): string {
  return typeof value === STRING ? (value as string).trim() : '';
}
function declaredType(fields: Record<string, unknown>): string {
  return (
    DATA_TYPE_KEYS.map((key) => text(fields[key]))
      .find(Boolean)
      ?.toUpperCase() ?? ''
  );
}

/** 只沿唯一语义模型的直接列映射读取类型，不解析 SQL、不猜测表达式或聚合结果精度。 */
export async function resolveResourceDataType(
  presentation: DbtResourcePresentation,
  lineage: DbtLineage | undefined,
  projectId: string,
  api: DataTypeApi,
): Promise<ResourceDataType> {
  const definition = presentation.nativeDetails;
  const attributes = Object.assign({}, ...presentation.sections.map((section) => section.fields));
  const explicit =
    declaredType(attributes) || declaredType(definition) || declaredType(record(definition.meta));
  if (explicit) return { label: explicit, description: '来自当前发布版本的类型声明。' };
  const params = record(definition.type_params);
  const aggregation = text(record(params.metric_aggregation_params).agg).toLowerCase();
  const metric = presentation.kind === METRIC;
  if (metric && COUNT_AGGREGATIONS.has(aggregation)) return INTEGER;
  const numeric = metric && (NUMERIC_AGGREGATIONS.has(aggregation) || definition.type === RATIO);
  const fallback = numeric ? NUMERIC : UNKNOWN;
  if (metric && !numeric && !PRESERVING_AGGREGATIONS.has(aggregation)) return fallback;
  const expression = text(
    metric ? params.expr : (definition.expr ?? attributes.expression ?? definition.name),
  );
  if (!IDENTIFIER.test(expression)) return fallback;
  if (!lineage) return fallback;
  if (
    lineage.releaseId !== presentation.releaseId ||
    lineage.resourceId !== presentation.resourceId
  )
    throw new Error(INVALID_METADATA);
  const parents = [
    ...new Set(
      lineage.dependencies
        .filter((edge) => edge.downstreamResourceId === presentation.resourceId)
        .map((edge) => edge.upstreamResourceId),
    ),
  ];
  if (parents.length !== 1) return fallback;
  const owner = await api.getDbtResource(projectId, parents[0], presentation.releaseId);
  if (owner.resourceId !== parents[0]) throw new Error(INVALID_METADATA);
  if (owner.kind !== SEMANTIC_MODEL) return fallback;
  const ownerDefinition = record(JSON.parse(owner.definitionJson));
  const models = record(ownerDefinition.depends_on).nodes;
  if (!Array.isArray(models) || models.length !== 1 || !text(models[0])) return fallback;
  const modelId = text(models[0]);
  let model = await api.getDbtPresentation(projectId, modelId, presentation.releaseId);
  if (model.resourceId !== modelId || model.releaseId !== presentation.releaseId)
    throw new Error(INVALID_METADATA);
  if (!PHYSICAL_KINDS.has(model.kind)) return fallback;
  if (hasSerializedNode(model))
    model = restoreArtifactPresentation(
      model,
      await api.getDbtResource(projectId, modelId, presentation.releaseId),
    );
  const columnName = expression.replace(QUOTES, '');
  // 优先物理列元数据的 type；声明式 columns 只有 data_type，不能把维度的 categorical 当成列类型。
  const types = model.sections.flatMap((section) => {
    const columns = section.fields.columns;
    const column = record(
      Array.isArray(columns)
        ? columns.find((item) => record(item).name === columnName)
        : record(columns)[columnName],
    );
    const type = text(column[COLUMN_TYPE]) || declaredType(column);
    return type ? [type.toUpperCase()] : [];
  });
  const uniqueTypes = [...new Set(types)];
  if (uniqueTypes.length !== 1) return fallback;
  const columnType = uniqueTypes[0];
  return numeric
    ? {
        ...NUMERIC,
        description: `输入字段 ${owner.name ?? text(ownerDefinition.name)}.${columnName}：${columnType}；聚合可能改变结果精度，以引擎返回的类型为准。`,
      }
    : {
        label: columnType,
        description: `来自当前发布版本的源字段 ${owner.name ?? text(ownerDefinition.name)}.${columnName}。`,
      };
}
