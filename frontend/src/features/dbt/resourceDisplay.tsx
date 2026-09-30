import { Tag } from 'antd';

export const RESOURCE_LABELS: Record<string, string> = {
  METRIC: '指标',
  DIMENSION: '维度',
  SEMANTIC_MODEL: '语义模型',
  MODEL: '物理模型',
  TABLE: '数据表',
  VIEW: '视图',
  SOURCE: '数据源',
};
const FIELD_LABELS: Record<string, string> = {
  type: '类型',
  label: '业务名称',
  description: '业务说明',
  type_params: '类型参数',
  expr: '表达式',
  expression: '表达式',
  filter: '筛选条件',
  filters: '筛选条件',
  name: '名称',
  measure: '度量',
  measures: '度量',
  agg: '聚合方式',
  dimensions: '维度',
  entities: '实体',
  defaults: '默认配置',
  model: '物理模型',
  relation: '物理关系',
  columns: '字段',
  config: '配置',
  depends_on: '依赖',
  metricType: '指标类型',
  definitionSummary: '定义说明',
  inputMetricIds: '输入指标',
  datasetIds: '所属数据集',
  timeConfig: '时间配置',
  datasetId: '所属数据集',
  dimensionType: '维度类型',
  timeGranularity: '时间粒度',
  modelResourceId: '物理模型',
  dimensionIds: '维度',
  defaultTimeDimension: '默认时间维度',
  origin: '物理来源',
  metrics: '关联指标',
  numerator: '分子',
  denominator: '分母',
  is_private: '私有指标',
  join_to_timespine: '补齐时间序列',
  metric_aggregation_params: '聚合配置',
  agg_params: '聚合参数',
  semantic_model: '语义模型',
  agg_time_dimension: '聚合时间维度',
  use_discrete_percentile: '离散百分位',
  use_approximate_percentile: '近似百分位',
};
const EMPTY = '—';
const BOOLEAN_LABELS = ['否', '是'];
const DOT = '.';
const OBJECT_TYPE = 'object';
const BOOLEAN_TYPE = 'boolean';
const SUMMARY_KEYS = ['name', 'expr', 'agg'];
const SUMMARY_SEPARATOR = ' · ';
const FIELD_COUNT = ' 个字段';
const ITEM_COUNT = ' 项';
export const resourceName = (id: string) => id.split(DOT).at(-1) ?? id;

// 概览省略未填写的可选参数；false 和 0 是有效定义，完整原值仍可在原生属性中查看。
function hasValue(value: unknown): boolean {
  if (value == null || value === '') return false;
  if (Array.isArray(value)) return value.some(hasValue);
  if (typeof value === OBJECT_TYPE)
    return Object.values(value as Record<string, unknown>).some(hasValue);
  return true;
}

/** 标量直接阅读，嵌套配置通过摘要按需展开。 */
export function ResourceFields({ fields }: { fields: Record<string, unknown> }) {
  const entries = Object.entries(fields).filter(([, value]) => hasValue(value));
  if (!entries.length) return <span className="resource-muted">{EMPTY}</span>;
  return (
    <dl className="resource-fields">
      {entries.map(([key, value]) => (
        <div className="resource-field" key={key}>
          <dt title={key}>{FIELD_LABELS[key] ?? key}</dt>
          <dd>
            <ResourceValue value={value} />
          </dd>
        </div>
      ))}
    </dl>
  );
}
function ResourceValue({ value }: { value: unknown }) {
  if (value == null) return <span className="resource-muted">{EMPTY}</span>;
  if (Array.isArray(value))
    return value.length ? (
      <details className="resource-nested">
        <summary>
          {value.length}
          {ITEM_COUNT}
        </summary>
        <div className="resource-values">
          {value.map((item, index) => (
            <div className="resource-value" key={index}>
              <ResourceValue value={item} />
            </div>
          ))}
        </div>
      </details>
    ) : (
      <span className="resource-muted">{EMPTY}</span>
    );
  if (typeof value === OBJECT_TYPE) {
    const fields = value as Record<string, unknown>;
    const preview = SUMMARY_KEYS.map((key) => fields[key])
      .filter((item) => hasValue(item) && typeof item !== OBJECT_TYPE)
      .map(String)
      .join(SUMMARY_SEPARATOR);
    const count = Object.values(fields).filter(hasValue).length;
    return (
      <details className="resource-nested">
        <summary>
          {preview ? preview + SUMMARY_SEPARATOR : ''}
          {count}
          {FIELD_COUNT}
        </summary>
        <ResourceFields fields={fields} />
      </details>
    );
  }
  if (typeof value === BOOLEAN_TYPE) return <Tag>{BOOLEAN_LABELS[Number(value)]}</Tag>;
  return <span className="resource-scalar">{String(value) || EMPTY}</span>;
}
