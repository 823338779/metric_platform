import { Alert, Button, Card, Input, InputNumber, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type {
  DbtQueryFilter,
  DbtQueryOptions,
  DbtQueryRequest,
  DbtQueryStatus,
} from '../../api/types';
import { DbtDimensionValues } from './DbtDimensionValues';
import { DbtQueryResults } from './DbtQueryResults';

type QueryApi = Pick<
  MetricPlatformApi,
  | 'submitDbtQuery'
  | 'explainDbtQuery'
  | 'getDbtQuery'
  | 'getDbtCatalog'
  | 'getDbtQueryOptions'
  | 'submitDbtDimensionValues'
>;
const terminal = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

/** 指标、共同维度与 metric_time 粒度均从固定 release 的能力选项选择。 */
export function DbtQueryPage({ api = metricPlatformApi }: { api?: QueryApi }) {
  const { projectId = '' } = useParams();
  const [params] = useSearchParams();
  const releaseId = params.get('releaseId') ?? '';
  const initialMetric = params.get('metric') ?? '';
  const [availableMetrics, setAvailableMetrics] = useState<string[]>([]);
  const [metrics, setMetrics] = useState<string[]>(initialMetric ? [initialMetric] : []);
  const [options, setOptions] = useState<DbtQueryOptions>();
  const [groupBy, setGroupBy] = useState<string[]>([]);
  const [timeToken, setTimeToken] = useState<string>();
  const [filterField, setFilterField] = useState<string>();
  const [filterOperator, setFilterOperator] = useState('=');
  const [filterValue, setFilterValue] = useState('');
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [orderBy, setOrderBy] = useState<string[]>([]);
  const [limit, setLimit] = useState(100);
  const [queryId, setQueryId] = useState('');
  const [result, setResult] = useState<DbtQueryStatus>();
  const [error, setError] = useState('');
  const [loadingOptions, setLoadingOptions] = useState(false);
  const releaseGeneration = useRef(0);

  useEffect(() => {
    let active = true;
    releaseGeneration.current++;
    setMetrics(initialMetric ? [initialMetric] : []);
    setAvailableMetrics([]);
    setOptions(undefined);
    setGroupBy([]);
    setTimeToken(undefined);
    setQueryId('');
    setResult(undefined);
    setError('');
    if (releaseId) {
      // 类型筛选先于分页；遍历指标页保证后续页仍能用于组合查询。
      const loadMetrics = async () => {
        const names: string[] = [];
        for (let page = 0; active; page++) {
          const catalog = await api.getDbtCatalog(projectId, releaseId, '', page, 100, 'METRIC');
          if (!active) return;
          if (catalog.releaseId !== releaseId) throw new Error('指标目录版本与请求不一致');
          names.push(
            ...catalog.resources
              .filter((resource) => resource.queryable)
              .map((resource) => resource.name),
          );
          if (catalog.resources.length < 100 || (page + 1) * 100 >= catalog.total) break;
        }
        if (active) setAvailableMetrics(names);
      };
      void loadMetrics().catch((cause: unknown) => {
        if (active)
          setError(
            cause instanceof ApiProblem && cause.problem.status === 410
              ? '该发布版本已清理'
              : cause instanceof Error && cause.message === '指标目录版本与请求不一致'
                ? cause.message
                : '指标目录读取失败',
          );
      });
    }
    return () => {
      active = false;
    };
  }, [api, projectId, releaseId, initialMetric]);

  useEffect(() => {
    let active = true;
    setOptions(undefined);
    setGroupBy([]);
    setTimeToken(undefined);
    setFilterField(undefined);
    setFilterValue('');
    setOrderBy([]);
    if (!releaseId || metrics.length === 0)
      return () => {
        active = false;
      };
    setLoadingOptions(true);
    void api
      .getDbtQueryOptions(projectId, releaseId, metrics)
      .then((value) => {
        if (active && value.releaseId === releaseId) setOptions(value);
        else if (active) setError('查询选项版本与请求不一致');
      })
      .catch((cause: unknown) => {
        if (active)
          setError(
            cause instanceof ApiProblem && cause.problem.status === 410
              ? '该发布版本已清理'
              : cause instanceof ApiProblem && cause.problem.status === 503
                ? 'dbt 运行服务暂不可用'
                : cause instanceof ApiProblem && cause.problem.status === 422
                  ? '当前指标组合无法查询'
                  : '查询选项读取失败',
          );
      })
      .finally(() => {
        if (active) setLoadingOptions(false);
      });
    return () => {
      active = false;
    };
  }, [api, projectId, releaseId, metrics]);

  useEffect(() => {
    if (!queryId) return;
    let active = true;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const value = await api.getDbtQuery(projectId, queryId);
        if (!active || value.releaseId !== releaseId) return;
        setResult(value);
        if (!terminal.has(value.status)) timer = window.setTimeout(() => void poll(), 2000);
      } catch (cause) {
        if (active)
          setError(
            cause instanceof ApiProblem && cause.problem.status === 410
              ? '该发布版本已清理'
              : '查询状态读取失败',
          );
      }
    };
    void poll();
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [api, projectId, releaseId, queryId]);

  const submit = async (explain: boolean) => {
    const generation = releaseGeneration.current;
    if (!releaseId || !options || options.releaseId !== releaseId || !metrics.length) {
      setError('请选择可查询的指标与发布版本');
      return;
    }
    setError('');
    setResult(undefined);
    setQueryId('');
    const filters: DbtQueryFilter[] =
      filterField && filterValue
        ? [
            {
              field: filterField,
              operator: filterOperator,
              value:
                filterOperator === 'IN'
                  ? filterValue.split(',').map((value) => value.trim())
                  : filterValue,
            },
          ]
        : [];
    const body: DbtQueryRequest = {
      releaseId,
      metrics,
      groupBy: [...groupBy, ...(timeToken ? [timeToken] : [])],
      filters,
      limit,
      ...(startTime ? { startTime: new Date(startTime).toISOString() } : {}),
      ...(endTime ? { endTime: new Date(endTime).toISOString() } : {}),
      ...(orderBy.length ? { orderBy } : {}),
    };
    try {
      const submission = explain
        ? await api.explainDbtQuery(projectId, body)
        : await api.submitDbtQuery(projectId, body);
      if (submission.releaseId === releaseId && generation === releaseGeneration.current)
        setQueryId(submission.queryId);
    } catch (cause) {
      if (generation !== releaseGeneration.current) return;
      setError(
        cause instanceof ApiProblem && cause.problem.status === 410
          ? '该发布版本已清理'
          : '查询提交失败',
      );
    }
  };
  const dimensionOptions =
    options?.dimensions.map((value) => ({ value: value.token, label: value.name })) ?? [];
  const disabled = !releaseId || !options || options.releaseId !== releaseId || loadingOptions;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {error && <Alert type="error" title={error} />}
      <Card
        title="dbt 固定版本查询"
        extra={<Typography.Text>releaseId：{releaseId || '未选择'}</Typography.Text>}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select
            mode="multiple"
            aria-label="指标"
            placeholder="选择指标"
            value={metrics}
            onChange={setMetrics}
            options={availableMetrics.map((name) => ({ value: name, label: name }))}
            style={{ minWidth: 260 }}
          />
          {metrics.length > 1 &&
            options &&
            !options.dimensions.length &&
            !options.timeDimensions.length && <Alert type="warning" title="所选指标没有共同维度" />}
          <Select
            mode="multiple"
            aria-label="维度"
            placeholder="选择共同维度"
            value={groupBy}
            onChange={setGroupBy}
            disabled={disabled}
            options={dimensionOptions}
            style={{ minWidth: 260 }}
          />
          <Select
            aria-label="日期粒度"
            placeholder="选择 metric_time 日期粒度"
            allowClear
            value={timeToken}
            onChange={setTimeToken}
            disabled={disabled}
            options={
              options?.timeDimensions.map((item) => ({
                value: item.token,
                label: `metric_time · ${item.granularity}`,
              })) ?? []
            }
            style={{ minWidth: 260 }}
          />
          <Space wrap>
            <Input
              aria-label="开始时间"
              type="datetime-local"
              value={startTime}
              onChange={(event) => setStartTime(event.target.value)}
            />
            <Input
              aria-label="结束时间"
              type="datetime-local"
              value={endTime}
              onChange={(event) => setEndTime(event.target.value)}
            />
          </Space>
          <Space wrap>
            <Select
              aria-label="筛选维度"
              placeholder="选择筛选维度"
              allowClear
              value={filterField}
              onChange={setFilterField}
              disabled={disabled}
              options={dimensionOptions}
              style={{ minWidth: 180 }}
            />
            <Select
              aria-label="筛选操作符"
              value={filterOperator}
              onChange={setFilterOperator}
              disabled={disabled}
              options={(options?.allowedFilters ?? []).map((value) => ({ value, label: value }))}
              style={{ minWidth: 90 }}
            />
            <Input
              aria-label="筛选值"
              value={filterValue}
              onChange={(event) => setFilterValue(event.target.value)}
              placeholder={filterOperator === 'IN' ? '多个值用逗号分隔' : '筛选值'}
              style={{ width: 180 }}
            />
            {filterField && !disabled && (
              <DbtDimensionValues
                key={`${releaseId}:${filterField}`}
                projectId={projectId}
                releaseId={releaseId}
                metrics={metrics}
                dimension={filterField}
                filters={
                  filterValue
                    ? [{ field: filterField, operator: filterOperator, value: filterValue }]
                    : []
                }
                onSelect={(value) => setFilterValue(String(value))}
                api={api}
              />
            )}
          </Space>
          <Select
            mode="multiple"
            aria-label="排序"
            placeholder="选择排序字段"
            value={orderBy}
            onChange={setOrderBy}
            disabled={disabled}
            style={{ minWidth: 260 }}
            options={[
              ...dimensionOptions,
              ...(options?.timeDimensions.map((item) => ({
                value: item.token,
                label: `metric_time · ${item.granularity}`,
              })) ?? []),
              ...metrics.map((value) => ({ value, label: value })),
            ]}
          />
          <InputNumber
            aria-label="行数"
            min={1}
            max={10000}
            value={limit}
            onChange={(value) => setLimit(value ?? 100)}
          />
          <Space>
            <Button type="primary" disabled={disabled} onClick={() => void submit(false)}>
              运行查询
            </Button>
            <Button disabled={disabled} onClick={() => void submit(true)}>
              解释查询
            </Button>
          </Space>
        </Space>
      </Card>
      {queryId && <Typography.Text>queryId：{queryId}</Typography.Text>}
      {result && <DbtQueryResults result={result} />}
    </Space>
  );
}
