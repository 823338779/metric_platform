import { Alert, Button, Input, Typography } from 'antd';
import { PlayCircleOutlined, CodeOutlined, FilterOutlined } from '@ant-design/icons';
import {
  AnalysisEmpty,
  ChoiceChips,
  MetricLibrary,
  SelectedMetrics,
  TimeRange,
} from './AnalysisControls';
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
import { businessTimeToIso } from './businessTime';

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
const HISTORICAL_RELEASE_DESCRIPTION = '历史发布仅保留记录，不能发起新查询。';

/** 指标、共同维度与 metric_time 粒度均从固定 release 的能力选项选择。 */
export function DbtQueryPage({ api = metricPlatformApi }: { api?: QueryApi }) {
  const { projectId = '' } = useParams();
  const [params] = useSearchParams();
  const releaseId = params.get('releaseId') ?? '';
  const initialMetric = params.get('metric') ?? '';
  const [activeReleaseId, setActiveReleaseId] = useState<string | null>(null);
  const [activeChecked, setActiveChecked] = useState(false);
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

  // 先读取活动指针，再决定是否加载当前版本的指标与查询选项。
  useEffect(() => {
    let active = true;
    setActiveReleaseId(null);
    setActiveChecked(false);
    void api
      .listDbtProjects()
      .then((projects) => {
        if (active)
          setActiveReleaseId(
            projects.find((project) => project.projectId === projectId)?.activeReleaseId ?? null,
          );
        if (active) setActiveChecked(true);
      })
      .catch(() => {
        if (active) {
          setActiveReleaseId(null);
          setActiveChecked(true);
        }
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
    if (releaseId && activeChecked && (!activeReleaseId || releaseId === activeReleaseId)) {
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
  }, [api, projectId, releaseId, initialMetric, activeChecked, activeReleaseId]);

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
    if (
      !releaseId ||
      !activeChecked ||
      (activeReleaseId && releaseId !== activeReleaseId) ||
      metrics.length === 0
    )
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
  }, [api, projectId, releaseId, metrics, activeChecked, activeReleaseId]);

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
    if (startTime && endTime && businessTimeToIso(startTime) > businessTimeToIso(endTime)) {
      setError('开始时间不能晚于结束时间');
      return;
    }
    if (!Number.isInteger(limit) || limit < 1 || limit > 10000) {
      setError('返回行数应为 1 至 10000 的整数');
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
      ...(startTime ? { startTime: businessTimeToIso(startTime) } : {}),
      ...(endTime ? { endTime: businessTimeToIso(endTime) } : {}),
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
    !releaseId ||
    !activeChecked ||
    (activeReleaseId && releaseId !== activeReleaseId) ||
    !options ||
    options.releaseId !== releaseId ||
    loadingOptions ||
    busy;
  const filterValues =
    filterOperator === 'IN' ? filterValue.split(',').map((value) => value.trim()) : filterValue;
  const metricChoices = availableMetrics.map((name) => ({ value: name, label: name }));
  const changeMetrics = (value: string[]) => {
    releaseGeneration.current++;
    setMetrics(value);
    setResult(undefined);
    setQueryId('');
    setSubmitting(false);
    setSubmittedRequest(undefined);
  };
  return (
    <section className="analysis-page">
      <header className="analysis-heading">
        <div>
          <Link
            to={`/dbt/projects/${encodeURIComponent(projectId)}/catalog?releaseId=${encodeURIComponent(releaseId)}&kind=METRIC`}
          >
            返回指标目录
          </Link>
          <h1>分析探索</h1>
        </div>
        <Typography.Text type="secondary">releaseId：{releaseId || '未选择'}</Typography.Text>
      </header>
      {activeReleaseId && releaseId && activeReleaseId !== releaseId && (
        <Alert
          type="warning"
          title={HISTORICAL_RELEASE_TITLE}
          description={`${HISTORICAL_RELEASE_DESCRIPTION}当前发布版本为 ${activeReleaseId}。`}
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
      <div className="analysis-studio">
        <MetricLibrary options={metricChoices} selected={metrics} onChange={changeMetrics} />
        <main className="analysis-canvas">
          <section className="analysis-builder" aria-label="查询设置">
            <header>
              <h2>这次想了解什么？</h2>
              <span>按需分组，逐步缩小范围</span>
            </header>
            <div className="analysis-builder-row">
              <h3>分析指标</h3>
              <SelectedMetrics
                options={metricChoices}
                selected={metrics}
                onChange={changeMetrics}
              />
            </div>
            {metrics.length > 1 &&
              options &&
              !options.dimensions.length &&
              !options.timeDimensions.length && (
                <Alert type="warning" title="所选指标没有共同维度" />
              )}
            <div className="analysis-builder-row">
              <div>
                <h3>分组维度</h3>
                <small>可直接多选</small>
              </div>
              <ChoiceChips
                label="维度"
                options={dimensionOptions}
                selected={groupBy}
                onChange={setGroupBy}
                disabled={!!disabled}
                emptyText={
                  metrics.length
                    ? loadingOptions
                      ? '正在读取共同维度…'
                      : '当前指标没有可用分组，查询将显示汇总值'
                    : '先选择指标，再选择可用维度'
                }
              />
            </div>
            {!!options?.timeDimensions.length && (
              <div className="analysis-builder-row">
                <h3>日期粒度</h3>
                <ChoiceChips
                  label="日期粒度"
                  single
                  options={options.timeDimensions.map((item) => ({
                    value: item.token,
                    label: timeLabels[item.granularity] ?? item.granularity,
                  }))}
                  selected={timeToken ? [timeToken] : []}
                  onChange={(values) => setTimeToken(values[0])}
                  disabled={!!disabled}
                />
              </div>
            )}
            <div className="analysis-builder-row">
              <h3>时间范围</h3>
              <TimeRange
                start={startTime}
                end={endTime}
                onChange={(from, to) => {
                  setStartTime(from);
                  setEndTime(to);
                }}
              />
            </div>
            <details className="analysis-advanced">
              <summary>
                <FilterOutlined /> 筛选与排序
                <span>{filterField || orderBy.length ? '已配置' : '按需添加'}</span>
              </summary>
              <div className="analysis-builder-row">
                <h3>筛选维度</h3>
                <div className="analysis-filter-controls">
                  <ChoiceChips
                    label="筛选维度"
                    single
                    options={dimensionOptions}
                    selected={filterField ? [filterField] : []}
                    onChange={(values) => setFilterField(values[0])}
                    disabled={!!disabled}
                  />
                  {filterField && (
                    <>
                      <ChoiceChips
                        label="筛选操作符"
                        single
                        options={(options?.allowedFilters ?? []).map((value) => ({
                          value,
                          label: value,
                        }))}
                        selected={[filterOperator]}
                        onChange={(values) => {
                          if (values[0]) setFilterOperator(values[0]);
                        }}
                        disabled={!!disabled}
                      />
                      <Input
                        aria-label="筛选值"
                        value={filterValue}
                        onChange={(event) => setFilterValue(event.target.value)}
                        placeholder={filterOperator === 'IN' ? '多个值用逗号分隔' : '输入筛选值'}
                      />
                    </>
                  )}
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
                </div>
              </div>
              <div className="analysis-builder-row">
                <h3>排序</h3>
                <ChoiceChips
                  label="排序"
                  options={[...groupBy, ...(timeToken ? [timeToken] : []), ...metrics].flatMap(
                    (value) => [
                      { value, label: `${value} ↑` },
                      { value: `-${value}`, label: `${value} ↓` },
                    ],
                  )}
                  selected={orderBy}
                  onChange={(values) => {
                    const added = values.find((value) => !orderBy.includes(value));
                    setOrderBy(
                      added
                        ? values.filter(
                            (value) =>
                              value !== (added.startsWith('-') ? added.slice(1) : `-${added}`),
                          )
                        : values,
                    );
                  }}
                  disabled={!!disabled}
                />
              </div>
            </details>
            <footer className="analysis-runbar">
              <label>
                最多返回{' '}
                <input
                  aria-label="行数"
                  type="number"
                  min={1}
                  max={10000}
                  value={limit}
                  onChange={(event) => setLimit(Number(event.target.value))}
                />{' '}
                行
              </label>
              <div>
                <Button
                  aria-label="解释查询"
                  icon={<CodeOutlined />}
                  disabled={!!disabled}
                  loading={busy && explainRequested}
                  onClick={() => void submit(true)}
                >
                  解释查询
                </Button>
                <Button
                  type="primary"
                  aria-label="运行查询"
                  icon={<PlayCircleOutlined />}
                  disabled={!!disabled}
                  loading={busy && !explainRequested}
                  onClick={() => void submit(false)}
                >
                  运行查询
                </Button>
              </div>
            </footer>
          </section>
          <section className="analysis-result" aria-label="查询结果" ref={resultPanel}>
            <header>
              <h2>查询结果</h2>
              <span>{queryId ? `queryId：${queryId}` : '等待运行'}</span>
            </header>
            {submittedRequest && (
              <Typography.Paragraph type="secondary">
                本次查询：{submittedRequest.metrics.join('、')} ·{' '}
                {submittedRequest.groupBy?.length ? submittedRequest.groupBy.join(' / ') : '汇总值'}
                。修改条件后点击运行查询刷新结果。
              </Typography.Paragraph>
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
              !busy && <AnalysisEmpty />
            )}
          </section>
        </main>
      </div>
    </section>
  );
}
