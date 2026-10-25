import { Alert, Button, Empty, Input } from 'antd';
import {
  PlayCircleOutlined,
  CodeOutlined,
  ReloadOutlined,
  ArrowLeftOutlined,
  FilterOutlined,
} from '@ant-design/icons';
import { AnalysisEmpty, ChoiceChips, MetricLibrary, TimeRange } from './AnalysisControls';
import { QuerySelection, ASCENDING, type QueryDirection } from './QuerySelection';
import { DbtQueryResults } from './DbtQueryResults';
import { manifestResources, publishedResult } from './publishedResourceAdapter';
import { businessTimeToIso } from './businessTime';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import {
  buildCatalogApi,
  type BuildCatalogApi,
  type PublishedOption,
  type PublishedQuery,
  type PublishedResource,
  type PublishedResult,
  type BuildView,
} from '../../api/buildCatalogApi';

const EMPTY = '';
const QUERY = 'QUERY';
const EXPLAIN = 'EXPLAIN';
const PREVIEW = 'PREVIEW';
const DIMENSION_VALUES = 'DIMENSION_VALUES';
const COPY = {
  context: '版本与执行环境',
  description: '选择指标，组合维度，探索数据背后的变化。',
  conditions: '查询条件',
  advanced: '筛选与排序',
  current: '结果与当前条件一致',
  stale: '条件已修改，运行查询以更新结果',
  running: '正在执行查询，结果将在完成后更新',
};

const errorText = (error: unknown) =>
  error instanceof ApiProblem
    ? `${error.problem.code ?? error.problem.status}：${error.problem.detail}`
    : `读取或查询失败，当前显示内容可能来自上次读取，请重试。${error instanceof Error ? error.message : EMPTY}`;
/** 查询表单以固定 buildId 为生命周期边界，保留选择交互与用户排序。 */
export function PublishedWorkbench({ api = buildCatalogApi }: { api?: BuildCatalogApi }) {
  const { buildId: routeBuildId } = useParams();
  const [params] = useSearchParams();
  const buildId = routeBuildId ?? params.get('buildId') ?? EMPTY;
  return <FixedPublishedWorkbench key={buildId} api={api} />;
}
function FixedPublishedWorkbench({ api }: { api: BuildCatalogApi }) {
  const { projectId = EMPTY, buildId: routeBuildId } = useParams();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const buildId = routeBuildId ?? params.get('buildId') ?? EMPTY;
  const [identity, setIdentity] = useState<BuildView | null>(null);
  const [identityError, setIdentityError] = useState(false);
  const [availableBuild, setAvailableBuild] = useState<string | null>(null);
  const initialMetric = params.get('metricResourceId') ?? EMPTY;
  const [submittedMode, setSubmittedMode] = useState<PublishedQuery['mode']>(QUERY);
  const [submittedMetrics, setSubmittedMetrics] = useState<string[]>([]);
  const [metrics, setMetrics] = useState<PublishedResource[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [grains, setGrains] = useState<Record<string, string>>({});
  const [availableDimensions, setAvailableDimensions] = useState<PublishedOption[]>([]);
  const [availableDimensionsReady, setAvailableDimensionsReady] = useState(false);
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
  const [sortFields, setSortFields] = useState<string[]>([]);
  const [sortDirections, setSortDirections] = useState<Record<string, QueryDirection>>({});
  const generation = useRef(0);
  const submissionGeneration = useRef(0);
  const [refresh, setRefresh] = useState(0);
  /** 记录提交时的条件，避免异步返回后把旧条件的结果标成最新。 */
  const [resultSelection, setResultSelection] = useState(EMPTY);
  const currentSelection = JSON.stringify({
    selected,
    groups,
    grains,
    filterOption,
    operator,
    filterValue,
    start,
    end,
    limit,
    sortFields,
    sortDirections,
  });

  useEffect(() => {
    if (!buildId) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function read() {
      try {
        const value = await api.build(buildId);
        if (!active) return;
        if (value.buildId !== buildId) throw new Error('构建身份不一致');
        setIdentity(value);
        setIdentityError(false);
        const deployment = await api.current({
          repository: value.repository,
          branchName: value.branchName,
          environmentId: value.execution.environmentId,
        });
        if (active) {
          setAvailableBuild(deployment.activeBuildId);
          timer = setTimeout(() => void read(), 5000);
        }
      } catch {
        if (active) setIdentityError(true);
      }
    }
    void read();
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
    };
  }, [api, buildId]);
  useEffect(() => {
    // 版本改变立即清除旧选择，防止新标题下暂时展示旧目录。
    submissionGeneration.current++;
    setResult(null);
    setMetrics([]);
    setSelected(initialMetric ? [initialMetric] : []);
    setAvailableDimensions([]);
    setAvailableDimensionsReady(false);
    setBusy(false);
    return () => {
      submissionGeneration.current++;
    };
  }, [projectId, buildId, initialMetric]);

  async function latest() {
    if (!identity) return;
    try {
      const target = await api.current({
        repository: identity.repository,
        branchName: identity.branchName,
        environmentId: identity.execution.environmentId,
      });
      if (!target.activeBuildId) {
        setError('当前分支尚未部署');
        return;
      }
      if (target.activeBuildId !== buildId)
        navigate(`/dbt/builds/${encodeURIComponent(target.activeBuildId)}/query`);
      else setRefresh((value) => value + 1);
    } catch (failure) {
      setError(errorText(failure));
    }
  }
  useEffect(() => {
    const turn = ++generation.current;
    setError(EMPTY);
    if (!buildId) {
      setError('请从固定构建目录进入查询');
      return;
    }
    setLoading(true);
    const load = async () => {
      try {
        {
          const manifest = await api.catalog(buildId);
          const all = manifestResources(manifest).filter((item) => item.kind === 'METRIC');
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
  }, [api, projectId, buildId, initialMetric, refresh]);

  useEffect(() => {
    setSortFields((fields) => fields.filter((id) => selected.includes(id) || groups.includes(id)));
  }, [selected, groups]);

  useEffect(() => {
    let active = true;
    setAvailableDimensionsReady(false);
    setAvailableDimensions([]);
    setGroups([]);
    setGrains({});
    setFilterOption(EMPTY);
    if (!selected.length || !buildId) return;
    const ids = metrics
      .filter((item) => selected.includes(item.resourceId))
      .map((item) => item.name)
      .sort();
    if (!ids.length) return;
    // 选项响应已经完成，切换选择后忽略旧响应。
    function observe(value: Awaited<ReturnType<BuildCatalogApi['availableDimensions']>>) {
      if (!active) return;
      if (value.buildId !== buildId) throw new Error('选项版本不匹配');
      setAvailableDimensions(value.dimensions);
      const metricTime = value.dimensions.find((option) => option.displayName === 'metric_time');
      if (metricTime) {
        const id = metricTime.optionId;
        setGroups([id]);
        setGrains(metricTime.granularities.includes('day') ? { [id]: 'day' } : {});
        setSortFields((fields) => [id, ...fields.filter((field) => field !== id)]);
        setSortDirections((directions) => ({ ...directions, [id]: ASCENDING }));
      }
      setAvailableDimensionsReady(true);
    }
    void api
      .availableDimensions(buildId, { metrics: ids })
      .then(observe)
      .catch((failure) => {
        if (active) setError(errorText(failure));
      });
    return () => {
      active = false;
    };
  }, [api, buildId, selected, metrics]);

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
    const metricNames = metrics
      .filter((item) => selected.includes(item.resourceId))
      .map((item) => item.name);
    const shared = {
      metrics: metricNames,
      limit,
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
      ...(start ? { startTime: businessTimeToIso(start) } : {}),
      ...(end ? { endTime: businessTimeToIso(end) } : {}),
    };
    // 各模式只发送自身支持的字段，PREVIEW 不携带指标，维度值不携带分组或排序。
    const selection: PublishedQuery =
      mode === PREVIEW
        ? { mode, selector: resourceId ?? EMPTY, limit }
        : mode === DIMENSION_VALUES
          ? { ...shared, mode, dimensionOptionId: groups[0] }
          : {
              ...shared,
              mode,
              groupBy: groups.map((optionId) => ({
                optionId,
                ...(grains[optionId] ? { grain: grains[optionId] } : {}),
              })),
              orderBy: sortFields.map((id) => ({
                fieldId: metrics.find((item) => item.resourceId === id)?.name ?? id,
                direction: sortDirections[id] ?? ASCENDING,
              })),
            };
    try {
      const accepted = await api.submit(buildId, selection);
      if (accepted.buildId !== buildId) throw new Error('查询版本不匹配');
      if (turn === submissionGeneration.current) {
        // 同步响应携带完整有界结果，不存在后续分页。
        setResult(accepted);
        setResultSelection(currentSelection);
        setSubmittedMode(mode);
        setSubmittedMetrics(
          metrics
            .filter((item) => selected.includes(item.resourceId))
            .map((item) => item.name ?? item.displayName),
        );
      }
    } catch (failure) {
      if (turn === submissionGeneration.current) {
        setError(errorText(failure));
      }
    } finally {
      if (turn === submissionGeneration.current) setBusy(false);
    }
  }

  const metricChoices = metrics.map((item) => ({
    value: item.resourceId,
    label: item.displayName || item.name,
    description: item.description,
  }));
  const dimensionChoices = availableDimensions.map((item) => ({
    value: item.optionId,
    label: item.displayName,
    dataType: item.dimensionType,
    granularities: item.granularities,
  }));
  const changeMetrics = (value: string[]) => setSelected(value);
  return (
    <section className="analysis-page query-workbench">
      <header className="analysis-heading">
        <div>
          <Link to={`/dbt/builds/${encodeURIComponent(buildId)}/catalog?${params}`}>
            <ArrowLeftOutlined aria-hidden /> 返回资源目录
          </Link>
          <h1>分析探索</h1>
          <p>{COPY.description}</p>
        </div>
        <div className="query-toolbar-actions">
          <Button
            icon={<ReloadOutlined aria-hidden />}
            loading={loading}
            onClick={() => void latest()}
          >
            刷新已发布版本
          </Button>
        </div>
      </header>
      <details className="query-context" aria-label="查询上下文">
        <summary>
          {COPY.context}
          <span>
            {identity?.branchName} · {identity?.execution.schema}
          </span>
        </summary>
        <p>
          仓库：{identity?.repository} · 分支：{identity?.branchName} · 环境：
          {identity?.execution
            ? `${identity.execution.catalog} / ${identity.execution.schema} · 配置版本 ${identity.execution.configVersion}`
            : undefined}
        </p>
        <p>
          固定版本：<code>{buildId || '尚未选择'}</code> · 源码提交：
          <code>{identity?.commitSha ?? '尚未读取'}</code>
        </p>
      </details>
      {identityError && (
        <Alert type="warning" title="分支身份详情读取失败，查询目标仍为上述固定 ID。" />
      )}
      {availableBuild && availableBuild !== buildId && (
        <Alert type="info" title="有效部署已更新，当前查询仍固定原构建；点击刷新后重新选择。" />
      )}
      {error && <Alert type="error" title={error} />}
      <div className="analysis-studio">
        <MetricLibrary
          options={metricChoices}
          selected={selected}
          onChange={changeMetrics}
          loading={loading}
        />
        <div className="analysis-canvas">
          <section className="analysis-builder" aria-label="查询设置">
            <header className="analysis-builder-heading">
              <h2>{COPY.conditions}</h2>
              <span>
                {selected.length} 个指标 · {groups.length} 个分组
              </span>
            </header>
            <div className="analysis-builder-row">
              <h3>分析指标</h3>
              <QuerySelection
                label="指标"
                showTypes={false}
                suggestWhenEmpty={false}
                options={metricChoices}
                selected={selected}
                onChange={changeMetrics}
              />
            </div>
            <div className="analysis-builder-row">
              <h3>分组维度</h3>
              <QuerySelection
                label="分组维度"
                options={dimensionChoices}
                selected={groups}
                onChange={setGroups}
                grains={grains}
                onGrainChange={(id, grain) => setGrains((current) => ({ ...current, [id]: grain }))}
                disabled={!availableDimensionsReady}
                emptyText={
                  selected.length
                    ? availableDimensionsReady
                      ? '当前指标没有可用分组，查询将显示汇总值'
                      : '正在读取共同维度…'
                    : '先选择指标，再选择可用维度'
                }
              />
            </div>
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
            <details className="analysis-advanced" open>
              <summary>
                <FilterOutlined aria-hidden />
                {COPY.advanced}
              </summary>
              <div className="analysis-builder-row">
                <h3>筛选维度</h3>
                <div className="analysis-filter-controls">
                  <QuerySelection
                    label="筛选维度"
                    single
                    suggestWhenEmpty={false}
                    disabled={!availableDimensionsReady}
                    options={dimensionChoices.filter(
                      (choice) =>
                        availableDimensions.find((option) => option.optionId === choice.value)
                          ?.operators.length,
                    )}
                    selected={filterOption ? [filterOption] : []}
                    onChange={(values) => {
                      setFilterOption(values[0] ?? EMPTY);
                      setOperator(
                        availableDimensions.find((option) => option.optionId === values[0])
                          ?.operators[0] ?? '=',
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
                          availableDimensions.find((option) => option.optionId === filterOption)
                            ?.operators ?? []
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
                <QuerySelection
                  label="排序字段"
                  showTypes={false}
                  suggestWhenEmpty={false}
                  options={[
                    ...metricChoices.filter((item) => selected.includes(item.value)),
                    ...dimensionChoices.filter((item) => groups.includes(item.value)),
                  ]}
                  selected={sortFields}
                  onChange={setSortFields}
                  directions={sortDirections}
                  onDirectionChange={(id, direction) =>
                    setSortDirections((current) => ({ ...current, [id]: direction }))
                  }
                  disabled={!availableDimensionsReady}
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
              <div className="query-toolbar-actions">
                <Button
                  disabled={!availableDimensionsReady || groups.length !== 1 || busy}
                  onClick={() => void submit(DIMENSION_VALUES)}
                >
                  维度值
                </Button>
                <Button
                  icon={<CodeOutlined aria-hidden />}
                  disabled={!availableDimensionsReady || busy}
                  onClick={() => void submit(EXPLAIN)}
                >
                  查看 SQL
                </Button>
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined aria-hidden />}
                  aria-label="查询"
                  disabled={!availableDimensionsReady || busy}
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
              <span>{result ? `版本 ${result.buildId}` : '等待运行'}</span>
            </header>
            {result ? (
              <>
                <p
                  className={
                    resultSelection === currentSelection && !busy
                      ? 'result-freshness'
                      : 'result-freshness is-stale'
                  }
                  role="status"
                >
                  {busy
                    ? COPY.running
                    : resultSelection === currentSelection
                      ? COPY.current
                      : COPY.stale}
                </p>
                {!result.columns?.length && !result.sql ? (
                  <Empty image={null} description="查询完成，没有返回数据列" />
                ) : (
                  <DbtQueryResults
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
        </div>
      </div>
    </section>
  );
}
