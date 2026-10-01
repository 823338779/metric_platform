import { Alert, Button, Empty, Input, Typography } from 'antd';
import { PlayCircleOutlined, CodeOutlined, FilterOutlined } from '@ant-design/icons';
import {
  AnalysisEmpty,
  GRAIN_LABELS,
  ChoiceChips,
  MetricLibrary,
  SelectedMetrics,
  TimeRange,
} from './AnalysisControls';
import { DbtQueryResults } from './DbtQueryResults';
import { publishedResult } from './publishedResourceAdapter';
import { businessTimeToIso } from './businessTime';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import {
  publishedCatalogApi,
  type PublishedCatalogApi,
  type PublishedOption,
  type PublishedQuery,
  type PublishedResource,
  type PublishedResult,
} from '../../api/publishedCatalogApi';

const EMPTY = '';
const QUERY = 'QUERY';
const EXPLAIN = 'EXPLAIN';
const PREVIEW = 'PREVIEW';
const DIMENSION_VALUES = 'DIMENSION_VALUES';
const READY = 'READY';
const FAILED = 'FAILED';
const errorText = (error: unknown) =>
  error instanceof ApiProblem && error.problem.status === 410
    ? '该版本已被替代，请刷新到最新发布版本并重新选择维度。'
    : '读取或查询失败，当前显示内容可能来自上次读取，请重试。';
/** v2 页面只渲染已发布属性，选择值使用资源和选项身份。 */
export function PublishedWorkbench({ api = publishedCatalogApi }: { api?: PublishedCatalogApi }) {
  const { projectId = EMPTY } = useParams();
  const [params, setParams] = useSearchParams();
  const releaseId = params.get('releaseId') ?? EMPTY;
  const initialMetric = params.get('metricResourceId') ?? EMPTY;
  const [submittedMode, setSubmittedMode] = useState<PublishedQuery['mode']>(QUERY);
  const [submittedMetrics, setSubmittedMetrics] = useState<string[]>([]);
  const [metrics, setMetrics] = useState<PublishedResource[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [grains, setGrains] = useState<Record<string, string>>({});
  const [options, setOptions] = useState<PublishedOption[]>([]);
  const [optionsReady, setOptionsReady] = useState(false);
  const [error, setError] = useState(EMPTY);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<PublishedResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [filterOption, setFilterOption] = useState(EMPTY);
  const [operator, setOperator] = useState('=');
  const [filterValue, setFilterValue] = useState(EMPTY);
  const [start, setStart] = useState(EMPTY);
  const [end, setEnd] = useState(EMPTY);
  const [limit, setLimit] = useState(1000);
  const [sortField, setSortField] = useState(EMPTY);
  const [sortDirection, setSortDirection] = useState<'ASC' | 'DESC'>('ASC');
  const generation = useRef(0);
  const submissionGeneration = useRef(0);
  const [pollAttempt, setPollAttempt] = useState(0);
  const submission = useRef<{ signature: string; body: PublishedQuery } | null>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    // 版本改变立即清除旧选择，防止新标题下暂时展示旧目录。
    submissionGeneration.current++;
    setResult(null);
    setMetrics([]);
    setSelected(initialMetric ? [initialMetric] : []);
    setOptions([]);
    setOptionsReady(false);
    setBusy(false);
    submission.current = null;
  }, [projectId, releaseId, initialMetric]);

  async function latest() {
    const turn = ++generation.current;
    try {
      const project = await api.publication(projectId);
      if (turn !== generation.current) return;
      const version = project.activePublication?.releaseId;
      if (!version) {
        setError('该项目尚未发布');
        return;
      }
      setParams((current) => {
        const next = new URLSearchParams(current);
        next.set('releaseId', version);
        if (version !== releaseId) {
          next.delete('resourceId');
          next.delete('metricResourceId');
        }
        return next;
      });
      setRefresh((value) => value + 1);
    } catch (failure) {
      if (turn === generation.current) setError(errorText(failure));
    }
  }

  useEffect(() => {
    const turn = ++generation.current;
    setError(EMPTY);
    if (!releaseId) {
      void latest();
      return;
    }
    setLoading(true);
    const load = async () => {
      try {
        {
          const all: PublishedResource[] = [];
          let number = 1;
          while (true) {
            const part = await api.catalog(projectId, releaseId, EMPTY, 'METRIC', number++);
            if (turn !== generation.current) return;
            if (part.releaseId !== releaseId) throw new Error('目录版本不匹配');
            all.push(...part.resources);
            if (all.length >= part.total || part.resources.length === 0) break;
          }
          if (turn !== generation.current) return;
          setMetrics(all);
          setSelected((previous) =>
            previous.filter((id) => all.some((item) => item.resourceId === id)),
          );
        }
      } catch (failure) {
        if (turn === generation.current) setError(errorText(failure));
      } finally {
        if (turn === generation.current) setLoading(false);
      }
    };
    void load();
    return () => {
      generation.current++;
    };
  }, [api, projectId, releaseId, initialMetric, refresh]);

  useEffect(() => {
    if (!selected.includes(sortField) && !groups.includes(sortField)) setSortField(EMPTY);
  }, [selected, groups, sortField]);

  useEffect(() => {
    let active = true;
    setOptionsReady(false);
    setOptions([]);
    setGroups([]);
    setGrains({});
    setFilterOption(EMPTY);
    if (!selected.length || !releaseId) return;
    api
      .options(projectId, releaseId, selected)
      .then((value) => {
        if (active) {
          if (value.releaseId !== releaseId) throw new Error('选项版本不匹配');
          setOptions(value.options);
          setOptionsReady(true);
        }
      })
      .catch((failure) => {
        if (active) setError(errorText(failure));
      });
    return () => {
      active = false;
    };
  }, [api, projectId, releaseId, selected]);

  useEffect(() => {
    if (
      !result ||
      result.state === READY ||
      result.state === FAILED ||
      result.state === 'CANCELLED'
    )
      return;
    let active = true;
    const timer = setTimeout(() => {
      api
        .query(projectId, result.queryId)
        .then((value) => {
          if (active) {
            if (value.releaseId !== result.releaseId || value.queryId !== result.queryId)
              throw new Error('查询身份不匹配');
            setResult(value);
          }
        })
        .catch((failure) => {
          if (active) {
            setError(errorText(failure));
            setPollAttempt((value) => value + 1);
          }
        });
    }, 1000);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [api, projectId, result, pollAttempt]);

  async function submit(mode: PublishedQuery['mode'], resourceId?: string) {
    const turn = submissionGeneration.current;
    if (!Number.isInteger(limit) || limit < 1 || limit > 10000) {
      setError('返回行数应为 1 至 10000 的整数');
      return;
    }
    if (start && end && businessTimeToIso(start) > businessTimeToIso(end)) {
      setError('开始时间不能晚于结束时间');
      return;
    }
    setBusy(true);
    setError(EMPTY);
    const selection = {
      releaseId,
      mode,
      metricResourceIds: mode === PREVIEW ? [] : selected,
      groupBy:
        mode === PREVIEW
          ? []
          : groups.map((optionId) => ({
              optionId,
              ...(grains[optionId] ? { grain: grains[optionId] } : {}),
            })),
      limit,
      ...(mode === PREVIEW
        ? { datasetResourceId: resourceId }
        : {
            filters: filterOption
              ? [
                  {
                    optionId: filterOption,
                    operator,
                    value:
                      operator === 'IN'
                        ? filterValue.split(',').map((value) => value.trim())
                        : filterValue,
                  },
                ]
              : [],
            orderBy: sortField ? [{ fieldId: sortField, direction: sortDirection }] : [],
            ...(start ? { startTime: businessTimeToIso(start) } : {}),
            ...(end ? { endTime: businessTimeToIso(end) } : {}),
          }),
      ...(mode === DIMENSION_VALUES ? { dimensionOptionId: groups[0] } : {}),
    };
    const signature = JSON.stringify(selection);
    if (submission.current?.signature !== signature)
      submission.current = {
        signature,
        body: { ...selection, idempotencyKey: crypto.randomUUID() },
      };
    try {
      const accepted = await api.submit(projectId, submission.current.body);
      if (accepted.releaseId !== releaseId) throw new Error('查询版本不匹配');
      if (turn === submissionGeneration.current) {
        setResult(accepted);
        setSubmittedMode(mode);
        setSubmittedMetrics(
          metrics
            .filter((item) => selected.includes(item.resourceId))
            .map((item) => item.name ?? item.displayName),
        );
        submission.current = null;
      }
    } catch (failure) {
      if (turn === submissionGeneration.current) setError(errorText(failure));
    } finally {
      if (turn === submissionGeneration.current) setBusy(false);
    }
  }

  const metricChoices = metrics.map((item) => ({
    value: item.resourceId,
    label: item.displayName || item.name,
    description: item.description,
  }));
  const dimensionChoices = options.map((item) => ({
    value: item.optionId,
    label: item.displayName,
  }));
  const changeMetrics = (value: string[]) => setSelected(value);
  return (
    <section className="analysis-page">
      <header className="analysis-heading">
        <div>
          <Link to={`/dbt/projects/${encodeURIComponent(projectId)}/catalog?${params}`}>
            返回资源目录
          </Link>
          <h1>分析探索</h1>
        </div>
        <Button loading={loading} onClick={() => void latest()}>
          刷新已发布版本
        </Button>
      </header>
      {error && <Alert type="error" title={error} />}
      <div className="analysis-studio">
        <MetricLibrary
          options={metricChoices}
          selected={selected}
          onChange={changeMetrics}
          loading={loading}
        />
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
                selected={selected}
                onChange={changeMetrics}
              />
            </div>
            <div className="analysis-builder-row">
              <div>
                <h3>分组维度</h3>
                <small>可直接多选</small>
              </div>
              <ChoiceChips
                label="分组维度"
                options={dimensionChoices}
                selected={groups}
                onChange={setGroups}
                disabled={!optionsReady}
                emptyText={
                  selected.length
                    ? optionsReady
                      ? '当前指标没有可用分组，查询将显示汇总值'
                      : '正在读取共同维度…'
                    : '先选择指标，再选择可用维度'
                }
              />
            </div>
            {options
              .filter(
                (option) => groups.includes(option.optionId) && option.granularities.length > 0,
              )
              .map((option) => (
                <div className="analysis-builder-row" key={option.optionId}>
                  <h3>{option.displayName}粒度</h3>
                  <ChoiceChips
                    label={`${option.displayName}时间粒度`}
                    single
                    options={option.granularities.map((grain) => ({
                      value: grain,
                      label: GRAIN_LABELS[grain] ?? grain,
                    }))}
                    selected={grains[option.optionId] ? [grains[option.optionId]] : []}
                    onChange={(values) =>
                      setGrains((current) => ({
                        ...current,
                        [option.optionId]: values[0] ?? EMPTY,
                      }))
                    }
                  />
                </div>
              ))}
            <div className="analysis-builder-row">
              <h3>时间范围</h3>
              <TimeRange
                start={start}
                end={end}
                onChange={(from, to) => {
                  setStart(from);
                  setEnd(to);
                }}
              />
            </div>
            <details className="analysis-advanced">
              <summary>
                <FilterOutlined /> 筛选与排序
                <span>{filterOption || sortField ? '已配置' : '按需添加'}</span>
              </summary>
              <div className="analysis-builder-row">
                <h3>筛选维度</h3>
                <div className="analysis-filter-controls">
                  <ChoiceChips
                    label="筛选维度"
                    single
                    options={options
                      .filter((option) => option.operators.length > 0)
                      .map((option) => ({ value: option.optionId, label: option.displayName }))}
                    selected={filterOption ? [filterOption] : []}
                    onChange={(values) => {
                      setFilterOption(values[0] ?? EMPTY);
                      setOperator(
                        options.find((option) => option.optionId === values[0])?.operators[0] ??
                          '=',
                      );
                      setFilterValue(EMPTY);
                    }}
                  />
                  {filterOption && (
                    <>
                      <ChoiceChips
                        label="操作符"
                        single
                        options={(
                          options.find((option) => option.optionId === filterOption)?.operators ??
                          []
                        ).map((value) => ({ value, label: value }))}
                        selected={[operator]}
                        onChange={(values) => {
                          if (values[0]) setOperator(values[0]);
                        }}
                      />
                      <Input
                        aria-label="筛选值"
                        value={filterValue}
                        onChange={(event) => setFilterValue(event.target.value)}
                        placeholder={operator === 'IN' ? '多个值用逗号分隔' : '输入筛选值'}
                      />
                    </>
                  )}
                </div>
              </div>
              <div className="analysis-builder-row">
                <h3>排序字段</h3>
                <div className="analysis-filter-controls">
                  <ChoiceChips
                    label="排序字段"
                    single
                    options={[
                      ...metricChoices.filter((item) => selected.includes(item.value)),
                      ...dimensionChoices.filter((item) => groups.includes(item.value)),
                    ]}
                    selected={sortField ? [sortField] : []}
                    onChange={(values) => setSortField(values[0] ?? EMPTY)}
                  />
                  {sortField && (
                    <ChoiceChips
                      label="排序方向"
                      single
                      options={[
                        { value: 'ASC', label: '升序' },
                        { value: 'DESC', label: '降序' },
                      ]}
                      selected={[sortDirection]}
                      onChange={(values) => {
                        if (values[0]) setSortDirection(values[0] as 'ASC' | 'DESC');
                      }}
                    />
                  )}
                </div>
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
                  disabled={!optionsReady || groups.length !== 1 || busy}
                  onClick={() => void submit(DIMENSION_VALUES)}
                >
                  维度值
                </Button>
                <Button
                  icon={<CodeOutlined />}
                  disabled={!optionsReady || busy}
                  onClick={() => void submit(EXPLAIN)}
                >
                  查看 SQL
                </Button>
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  aria-label="查询"
                  disabled={!optionsReady || busy}
                  loading={busy}
                  onClick={() => void submit(QUERY)}
                >
                  运行查询
                </Button>
              </div>
            </footer>
          </section>
          <section className="analysis-result" aria-label="查询结果" aria-live="polite">
            <header>
              <h2>查询结果</h2>
              <span>
                {result ? `查询 ${result.queryId} · 版本 ${result.releaseId}` : '等待运行'}
              </span>
            </header>
            {result ? (
              <>
                <p className="resource-muted">修改条件后，运行查询以刷新结果。</p>
                {result.state === READY && !result.columns?.length && !result.sql ? (
                  <Empty image={null} description="查询完成，没有返回数据列" />
                ) : (
                  <DbtQueryResults
                    key={result.queryId}
                    result={publishedResult(result, submittedMode)}
                    metricNames={submittedMetrics}
                  />
                )}
              </>
            ) : busy ? (
              <Alert type="info" title="查询已提交，正在读取结果…" />
            ) : (
              <AnalysisEmpty />
            )}
          </section>
        </main>
      </div>
    </section>
  );
}
