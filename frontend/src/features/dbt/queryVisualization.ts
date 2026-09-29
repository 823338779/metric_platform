import type { DbtQueryStatus } from '../../api/types';

const NUMERIC = /DECIMAL|NUMERIC|INT|FLOAT|DOUBLE|REAL|NUMBER/i;
const TIME = /DATE|TIME/i;
const CATEGORY = /CHAR|TEXT|STR|DATE|TIME/i;
const NUMBER_TEXT = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$/i;
const EMPTY = '（空值）';
const SERIES_LIMIT = 30;

/** 优先使用查询中的指标名称，防止数值型业务维度被误识别为指标。 */
export function visualizationFields(result: DbtQueryStatus, metricNames?: string[]) {
  const columns = (result.columns ?? []).map((column, index) => ({ ...column, index }));
  const measures = columns.filter((column) =>
    metricNames ? metricNames.includes(column.name) : NUMERIC.test(column.type),
  );
  const dimensions = columns.filter((column) => !measures.includes(column));
  return { measures, dimensions };
}

/** 图形只能接收安全数值；高精度文本继续通过明细表查看。 */
function chartNumber(value: unknown): number | null | undefined {
  if (value === null) return null;
  if (typeof value !== 'number' && typeof value !== 'string') return undefined;
  if (typeof value === 'string') {
    if (!NUMBER_TEXT.test(value)) return undefined;
    const digits = value
      .split(/e/i)[0]
      .replace(/[-+.]/g, '')
      .replace(/^0+|0+$/g, '');
    if (digits.length > 15) return undefined;
  }
  const number = Number(value);
  return Number.isFinite(number) && Math.abs(number) <= Number.MAX_SAFE_INTEGER
    ? number
    : undefined;
}

/** 每个图形点对应一条原始结果；不在浏览器中重算指标或填补缺失值。 */
export function buildVisualization(
  result: DbtQueryStatus,
  measure: number,
  x: number,
  seriesDimensions: number[],
  metricNames?: string[],
) {
  const { dimensions } = visualizationFields(result, metricNames);
  const column = result.columns?.[x];
  if (!column || !result.rows?.length || measure < 0) return { message: '' };
  if (!metricNames && (dimensions.length !== 1 || !CATEGORY.test(column.type)))
    return { message: '请选择指标探索，以明确多维度结果的展示方式。' };

  const categories = new Map<string, unknown>();
  const series = new Map<string, { name: string; values: Map<string, number | null> }>();
  for (const row of result.rows) {
    const value = chartNumber(row[measure]);
    if (value === undefined)
      return { message: '结果包含无法安全绘图的高精度或非数值数据，请查看原始明细。' };
    const categoryKey = JSON.stringify(row[x]);
    const seriesValues = seriesDimensions.map((index) => row[index]);
    const seriesKey = JSON.stringify(seriesValues);
    categories.set(categoryKey, row[x]);
    if (!series.has(seriesKey)) {
      series.set(seriesKey, {
        name: seriesValues.length
          ? seriesValues.map((cell) => (cell == null ? EMPTY : String(cell))).join(' / ')
          : (result.columns?.[measure].name ?? ''),
        values: new Map(),
      });
    }
    const item = series.get(seriesKey)!;
    if (item.values.has(categoryKey))
      return { message: '横轴与系列无法唯一区分每行数据，请补充系列维度；未对指标进行合并。' };
    item.values.set(categoryKey, value);
  }
  if (series.size > SERIES_LIMIT)
    return { message: '系列超过 30 组，请缩小筛选范围后查看图表；明细保留全部返回数据。' };
  const time = TIME.test(column.type);
  const keys = [...categories.keys()];
  if (time)
    keys.sort((a, b) =>
      String(categories.get(a) ?? '').localeCompare(String(categories.get(b) ?? '')),
    );
  return {
    message: '',
    time,
    categories: keys.map((key) => String(categories.get(key) ?? EMPTY)),
    series: [...series.values()].map((item) => ({
      name: item.name,
      data: keys.map((key) => item.values.get(key) ?? null),
    })),
  };
}
