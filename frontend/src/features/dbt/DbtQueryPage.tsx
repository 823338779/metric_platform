import { Alert, Button, Card, Empty, Input, InputNumber, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
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
  | 'listDbtProjects'
>;
const timeLabels: Record<string, string> = {
  day: '日',
  week: '周',
  month: '月',
  quarter: '季度',
  year: '年',
  hour: '小时',
  minute: '分钟',
};
const terminal = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);
const SQL_PROGRESS = '正在生成 SQL，请稍候…';
const QUERY_PROGRESS = '查询正在执行，请稍候…';
const HISTORICAL_RELEASE_TITLE = '当前查看的是历史发布版本';
const SWITCH_TO_ACTIVE_RELEASE = '切换到当前发布版本';

/** 指标、共同维度与 metric_time 粒度均从固定 release 的能力选项选择。 */
export function DbtQueryPage({ api = metricPlatformApi }: { api?: QueryApi }) {
  const { projectId = '' } = useParams();
  const [params] = useSearchParams();
  const releaseId = params.get('releaseId') ?? '';
  const initialMetric = params.get('metric') ?? '';
  const [activeReleaseId, setActiveReleaseId] = useState<string | null>(null);
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
  const [submitting, setSubmitting] = useState(false);
  // 保存本次提交的操作类型，用于按钮和结果区的加载提示。
  const [explainRequested, setExplainRequested] = useState(false);
  // 结果按已提交的指标解释，避免编辑查询条件后误读仍在展示的结果。
  const [submittedRequest, setSubmittedRequest] = useState<DbtQueryRequest>();
  const releaseGeneration = useRef(0);
  // 窄屏下结果位于表单下方，解释查询提交后将反馈区域带入视口。
  const resultPanel = useRef<HTMLDivElement>(null);

  // 查询仍固定在 URL 指定版本；读取当前发布指针只用于提示并提供显式切换入口。
  useEffect(() => {
    let active = true;
    setActiveReleaseId(null);
    void api
      .listDbtProjects()
      .then((projects) => {
        if (active)
          setActiveReleaseId(
            projects.find((project) => project.projectId === projectId)?.activeReleaseId ?? null,
          );
      })
      .catch(() => {
        if (active) setActiveReleaseId(null);
      });
    return () => {
      active = false;
    };
  }, [api, projectId]);

  useEffect(() => {
    if (explainRequested && submittedRequest)
      resultPanel.current?.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
  }, [explainRequested, submittedRequest, result?.status]);

  useEffect(() => {
    let active = true;
    releaseGeneration.current++;
    setMetrics(initialMetric ? [initialMetric] : []);
    setAvailableMetrics([]);
    setStartTime('');
    setEndTime('');
    setSubmittedRequest(undefined);
    setSubmitting(false);
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
    setLoadingOptions(false);
    setError('');
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
    if (startTime && endTime && new Date(startTime).getTime() > new Date(endTime).getTime()) {
      setError('开始时间不能晚于结束时间');
      return;
    }
    if (submitting) return;
    setExplainRequested(explain);
    setSubmitting(true);
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
      orderBy: orderBy.length ? orderBy : timeToken ? [timeToken] : [],
    };
    setSubmittedRequest(body);
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
    } finally {
      if (generation === releaseGeneration.current) setSubmitting(false);
    }
  };
  // 同名维度可能来自不同关联模型，展示令牌以让用户明确选择来源。
  const dimensionOptions =
    options?.dimensions.map((value) => ({
      value: value.token,
      label: options.dimensions.some(
        (other) => other.name === value.name && other.token !== value.token,
      )
        ? `${value.name}（${value.token}）`
        : value.name,
    })) ?? [];
  const busy = submitting || (!!queryId && !terminal.has(result?.status ?? '') && !error);
  const disabled =
    !releaseId || !options || options.releaseId !== releaseId || loadingOptions || busy;
  const filterValues =
    filterOperator === 'IN' ? filterValue.split(',').map((value) => value.trim()) : filterValue;
  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <div className="exploration-heading">
        <div>
          <Link
            to={`/dbt/projects/${encodeURIComponent(projectId)}/catalog?releaseId=${encodeURIComponent(releaseId)}&kind=METRIC`}
          >
            ← 返回指标目录
          </Link>
          <Typography.Title level={3}>指标探索</Typography.Title>
          <Typography.Text type="secondary">
            选择维度与日期范围，查询所选发布版本的数据。
          </Typography.Text>
        </div>
        <Typography.Text type="secondary">releaseId：{releaseId || '未选择'}</Typography.Text>
      </div>
      {activeReleaseId && releaseId && activeReleaseId !== releaseId && (
        <Alert
          type="warning"
          title={HISTORICAL_RELEASE_TITLE}
          description={`本页查询固定使用历史版本 ${releaseId}。当前发布版本为 ${activeReleaseId}。`}
          action={
            <Link
              to={`/dbt/projects/${encodeURIComponent(projectId)}/query?releaseId=${encodeURIComponent(activeReleaseId)}${initialMetric ? `&metric=${encodeURIComponent(initialMetric)}` : ''}`}
            >
              {SWITCH_TO_ACTIVE_RELEASE}
            </Link>
          }
        />
      )}
      {error && <Alert type="error" title={error} />}
      <div className="exploration-workspace">
        <Card title="查询设置">
          <div className="exploration-fields">
            <label>
              指标
              <Select
                mode="multiple"
                aria-label="指标"
                placeholder="选择指标"
                value={metrics}
                onChange={(value) => {
                  releaseGeneration.current++;
                  setMetrics(value);
                  setResult(undefined);
                  setQueryId('');
                  setSubmitting(false);
                  setSubmittedRequest(undefined);
                }}
                options={availableMetrics.map((name) => ({ value: name, label: name }))}
              />
            </label>
            {metrics.length > 1 &&
              options &&
              !options.dimensions.length &&
              !options.timeDimensions.length && (
                <Alert type="warning" title="所选指标没有共同维度" />
              )}
            <label>
              分组维度
              <Select
                mode="multiple"
                aria-label="维度"
                placeholder="不选择时显示汇总值"
                value={groupBy}
                onChange={setGroupBy}
                disabled={disabled}
                loading={loadingOptions}
                options={dimensionOptions}
              />
            </label>
            <Typography.Text type="secondary">
              可多选；选项来自当前指标可用的维度。多指标查询仅展示共同维度。
            </Typography.Text>
            <label>
              日期粒度
              <Select
                aria-label="日期粒度"
                placeholder="不按日期分组"
                allowClear
                value={timeToken}
                onChange={setTimeToken}
                disabled={disabled}
                options={
                  options?.timeDimensions.map((item) => ({
                    value: item.token,
                    label: timeLabels[item.granularity] ?? item.granularity,
                  })) ?? []
                }
              />
            </label>
            <div className="exploration-dates">
              <label>
                开始时间
                <Input
                  aria-label="开始时间"
                  type="datetime-local"
                  value={startTime}
                  onChange={(event) => setStartTime(event.target.value)}
                />
              </label>
              <label>
                结束时间
                <Input
                  aria-label="结束时间"
                  type="datetime-local"
                  value={endTime}
                  onChange={(event) => setEndTime(event.target.value)}
                />
              </label>
            </div>
            <label>
              筛选维度
              <Select
                aria-label="筛选维度"
                placeholder="选择需要限定取值的维度"
                allowClear
                value={filterField}
                onChange={setFilterField}
                disabled={disabled}
                options={dimensionOptions}
              />
            </label>
            <div className="exploration-filter">
              <Select
                aria-label="筛选操作符"
                value={filterOperator}
                onChange={setFilterOperator}
                disabled={disabled}
                options={(options?.allowedFilters ?? []).map((value) => ({ value, label: value }))}
              />
              <Input
                aria-label="筛选值"
                value={filterValue}
                onChange={(event) => setFilterValue(event.target.value)}
                placeholder={filterOperator === 'IN' ? '多个值用逗号分隔' : '筛选值'}
              />
            </div>
            {filterField && !disabled && (
              <DbtDimensionValues
                key={`${releaseId}:${metrics.join(',')}:${filterField}`}
                projectId={projectId}
                releaseId={releaseId}
                metrics={metrics}
                dimension={filterField}
                filters={
                  filterValue
                    ? [{ field: filterField, operator: filterOperator, value: filterValues }]
                    : []
                }
                onSelect={(value) => setFilterValue(String(value))}
                api={api}
              />
            )}
            <label>
              排序
              <Select
                mode="multiple"
                aria-label="排序"
                placeholder="默认按时间升序"
                value={orderBy}
                onChange={setOrderBy}
                disabled={disabled}
                options={[...groupBy, ...(timeToken ? [timeToken] : []), ...metrics].flatMap(
                  (value) => [
                    { value, label: `${value} ↑` },
                    { value: `-${value}`, label: `${value} ↓` },
                  ],
                )}
              />
            </label>
            <label>
              最多返回行数
              <InputNumber
                aria-label="行数"
                min={1}
                max={10000}
                value={limit}
                onChange={(value) => setLimit(value ?? 100)}
              />
            </label>
            <Space>
              <Button
                type="primary"
                disabled={disabled}
                loading={busy && !explainRequested}
                onClick={() => void submit(false)}
              >
                运行查询
              </Button>
              <Button
                disabled={disabled}
                loading={busy && explainRequested}
                onClick={() => void submit(true)}
              >
                解释查询
              </Button>
            </Space>
          </div>
        </Card>
        <div className="exploration-results" ref={resultPanel}>
          {submittedRequest && (
            <Typography.Paragraph type="secondary">
              本次查询：{submittedRequest.metrics.join('、')} ·{' '}
              {submittedRequest.groupBy?.length ? submittedRequest.groupBy.join(' / ') : '汇总值'}
              。修改条件后点击运行查询刷新结果。
            </Typography.Paragraph>
          )}
          {queryId && (
            <Typography.Paragraph type="secondary">queryId：{queryId}</Typography.Paragraph>
          )}
          {busy && !result && (
            <Alert type="info" title={explainRequested ? SQL_PROGRESS : QUERY_PROGRESS} />
          )}
          {result ? (
            <DbtQueryResults
              key={result.queryId}
              result={result}
              metricNames={submittedRequest?.metrics}
            />
          ) : (
            !busy && (
              <Card>
                <Empty description="选择指标与分析维度，运行后在这里查看图表和数据明细" />
              </Card>
            )
          )}
        </div>
      </div>
    </Space>
  );
}
