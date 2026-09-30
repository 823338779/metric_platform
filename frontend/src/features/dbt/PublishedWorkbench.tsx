import { Alert, Button, Card, Descriptions, Empty, Input, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom';
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
const LABELS: Record<string, string> = {
  METRIC: '指标',
  DIMENSION: '维度',
  SEMANTIC_MODEL: '数据集（语义模型）',
  TABLE: '表',
  VIEW: '视图',
};
const FIELD_LABELS: Record<string, string> = {
  metricType: '指标类型',
  definitionSummary: '定义说明',
  inputMetricIds: '输入指标',
  datasetIds: '所属数据集',
  filters: '定义筛选',
  timeConfig: '时间配置',
  datasetId: '所属数据集',
  dimensionType: '维度类型',
  expression: '表达式',
  timeGranularity: '时间粒度',
  modelResourceId: '物理模型',
  dimensionIds: '维度',
  entities: '实体',
  measures: '度量',
  defaultTimeDimension: '默认时间维度',
  origin: '物理来源',
  relation: '物理关系',
  columns: '字段',
};
const errorText = (error: unknown) =>
  error instanceof ApiProblem && error.problem.status === 410
    ? '该版本已被替代，请刷新到最新发布版本并重新选择维度。'
    : '读取或查询失败，当前显示内容可能来自上次读取，请重试。';
const display = (value: unknown) =>
  value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value);

/** v2 页面只渲染已发布属性，选择值使用资源和选项身份。 */
export function PublishedWorkbench({ api = publishedCatalogApi }: { api?: PublishedCatalogApi }) {
  const { projectId = EMPTY } = useParams();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const releaseId = params.get('releaseId') ?? EMPTY;
  const queryPage = location.pathname.endsWith('/query');
  const [resources, setResources] = useState<PublishedResource[]>([]);
  const [metrics, setMetrics] = useState<PublishedResource[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [options, setOptions] = useState<PublishedOption[]>([]);
  const [optionsReady, setOptionsReady] = useState(false);
  const [search, setSearch] = useState(EMPTY);
  const [kind, setKind] = useState(EMPTY);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState(EMPTY);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<PublishedResource | null>(null);
  const [trace, setTrace] = useState<unknown>(null);
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
  const inspection = useRef(0);
  const [pollAttempt, setPollAttempt] = useState(0);
  const submission = useRef<{ signature: string; body: PublishedQuery } | null>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    // 版本改变立即清除旧选择，防止新标题下暂时展示旧目录。
    submissionGeneration.current++;
    setResult(null);
    setResources([]);
    setMetrics([]);
    setSelected([]);
    setOptions([]);
    setOptionsReady(false);
    setBusy(false);
    submission.current = null;
  }, [projectId, releaseId]);

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
      setParams({ releaseId: version });
      setRefresh((value) => value + 1);
    } catch (failure) {
      if (turn === generation.current) setError(errorText(failure));
    }
  }

  useEffect(() => {
    const turn = ++generation.current;
    setDetail(null);
    setTrace(null);
    setError(EMPTY);
    if (!releaseId) {
      void latest();
      return;
    }
    setLoading(true);
    const load = async () => {
      try {
        const catalog = await api.catalog(projectId, releaseId, search, kind, page);
        if (turn !== generation.current) return;
        if (catalog.releaseId !== releaseId) throw new Error('目录版本不匹配');
        setResources(catalog.resources);
        setTotal(catalog.total);
        if (queryPage) {
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
  }, [api, projectId, releaseId, search, kind, page, queryPage, refresh]);

  useEffect(() => {
    if (!selected.includes(sortField) && !groups.includes(sortField)) setSortField(EMPTY);
  }, [selected, groups, sortField]);

  useEffect(() => {
    let active = true;
    setOptionsReady(false);
    setOptions([]);
    setGroups([]);
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
    if (!result || result.state === READY || result.state === FAILED) return;
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
    setBusy(true);
    setError(EMPTY);
    const selection = {
      releaseId,
      mode,
      metricResourceIds: mode === PREVIEW ? [] : selected,
      groupBy: mode === PREVIEW ? [] : groups.map((optionId) => ({ optionId })),
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
            ...(start ? { startTime: new Date(start).toISOString() } : {}),
            ...(end ? { endTime: new Date(end).toISOString() } : {}),
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
        submission.current = null;
      }
    } catch (failure) {
      if (turn === submissionGeneration.current) setError(errorText(failure));
    } finally {
      if (turn === submissionGeneration.current) setBusy(false);
    }
  }

  async function inspect(
    resource: PublishedResource,
    view?: 'lineage' | 'source' | 'native-details',
  ) {
    const turn = generation.current;
    const selection = ++inspection.current;
    try {
      if (view) {
        const value = await api.detail(projectId, releaseId, resource.resourceId, view);
        if (turn === generation.current && selection === inspection.current) setTrace(value);
      } else {
        const value = await api.resource(projectId, releaseId, resource.resourceId);
        if (turn === generation.current && selection === inspection.current) {
          setDetail(value);
          setTrace(null);
        }
      }
    } catch (failure) {
      if (turn === generation.current) setError(errorText(failure));
    }
  }

  return (
    <Card
      title={`${projectId} · ${queryPage ? '指标查询' : '已发布目录'}`}
      extra={<Button onClick={() => void latest()}>刷新已发布版本</Button>}
    >
      <Space orientation="vertical" style={{ width: '100%' }} size="middle">
        <Typography.Text type="secondary">当前版本：{releaseId || '尚未发布'}</Typography.Text>
        <Link
          to={`/dbt/projects/${encodeURIComponent(projectId)}/${queryPage ? 'catalog' : 'query'}?releaseId=${releaseId}`}
        >
          {queryPage ? '查看目录' : '探索指标'}
        </Link>
        {error && <Alert type="error" title={error} />}
        {queryPage ? (
          <>
            <label>
              指标
              <select
                aria-label="指标"
                multiple
                value={selected}
                onChange={(event) => {
                  setSelected(Array.from(event.target.selectedOptions, (option) => option.value));
                }}
              >
                {metrics.map((item) => (
                  <option key={item.resourceId} value={item.resourceId}>
                    {item.displayName}
                  </option>
                ))}
              </select>
            </label>
            <label>
              分组维度
              <select
                aria-label="分组维度"
                multiple
                value={groups}
                onChange={(event) =>
                  setGroups(Array.from(event.target.selectedOptions, (option) => option.value))
                }
              >
                {options.map((option) => (
                  <option key={option.optionId} value={option.optionId}>
                    {option.displayName}
                  </option>
                ))}
              </select>
            </label>
            <Space wrap>
              <label>
                排序字段
                <select
                  aria-label="排序字段"
                  value={sortField}
                  onChange={(event) => setSortField(event.target.value)}
                >
                  <option value="">默认顺序</option>
                  {metrics
                    .filter((item) => selected.includes(item.resourceId))
                    .map((item) => (
                      <option key={item.resourceId} value={item.resourceId}>
                        {item.displayName}
                      </option>
                    ))}
                  {options
                    .filter((item) => groups.includes(item.optionId))
                    .map((item) => (
                      <option key={item.optionId} value={item.optionId}>
                        {item.displayName}
                      </option>
                    ))}
                </select>
              </label>
              <select
                aria-label="排序方向"
                value={sortDirection}
                onChange={(event) => setSortDirection(event.target.value as 'ASC' | 'DESC')}
              >
                <option value="ASC">升序</option>
                <option value="DESC">降序</option>
              </select>
              <label>
                筛选维度
                <select
                  aria-label="筛选维度"
                  value={filterOption}
                  onChange={(event) => setFilterOption(event.target.value)}
                >
                  <option value="">不筛选</option>
                  {options
                    .filter((option) => option.operators.length > 0)
                    .map((option) => (
                      <option key={option.optionId} value={option.optionId}>
                        {option.displayName}
                      </option>
                    ))}
                </select>
              </label>
              <select
                aria-label="操作符"
                value={operator}
                onChange={(event) => setOperator(event.target.value)}
              >
                {(
                  options.find((option) => option.optionId === filterOption)?.operators ?? ['=']
                ).map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
              <Input
                aria-label="筛选值"
                value={filterValue}
                onChange={(event) => setFilterValue(event.target.value)}
                placeholder="IN 的多个值用逗号分隔"
              />
              <label>
                开始时间
                <input
                  aria-label="开始时间"
                  type="datetime-local"
                  value={start}
                  onChange={(event) => setStart(event.target.value)}
                />
              </label>
              <label>
                结束时间
                <input
                  aria-label="结束时间"
                  type="datetime-local"
                  value={end}
                  onChange={(event) => setEnd(event.target.value)}
                />
              </label>
              <label>
                行数
                <input
                  aria-label="行数"
                  type="number"
                  min={1}
                  max={10000}
                  value={limit}
                  onChange={(event) => setLimit(Number(event.target.value))}
                />
              </label>
            </Space>
            <Space>
              <Button
                aria-label="查询"
                disabled={!optionsReady || busy}
                onClick={() => void submit(QUERY)}
              >
                查询
              </Button>
              <Button disabled={!optionsReady || busy} onClick={() => void submit(EXPLAIN)}>
                查看 SQL
              </Button>
              <Button
                disabled={!optionsReady || groups.length !== 1 || busy}
                onClick={() => void submit(DIMENSION_VALUES)}
              >
                维度值
              </Button>
            </Space>
          </>
        ) : (
          <>
            <Space>
              <Input.Search
                placeholder="搜索资源"
                onSearch={(value) => {
                  setSearch(value);
                  setPage(1);
                }}
              />
              <select
                aria-label="资源类型"
                value={kind}
                onChange={(event) => {
                  setKind(event.target.value);
                  setPage(1);
                }}
              >
                <option value="">全部资源</option>
                {Object.entries(LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </Space>
            {!loading && !error && resources.length === 0 && (
              <Empty description="此版本没有匹配的资源" />
            )}
            {resources.map((resource) => (
              <Card
                key={resource.resourceId}
                size="small"
                title={resource.displayName}
                extra={<Tag>{LABELS[resource.kind]}</Tag>}
              >
                <p>{resource.description}</p>
                <Space>
                  <Button onClick={() => void inspect(resource)}>详情</Button>
                  {resource.capabilities.includes(PREVIEW) && (
                    <Button
                      disabled={busy}
                      onClick={() => void submit(PREVIEW, resource.resourceId)}
                    >
                      预览
                    </Button>
                  )}
                </Space>
              </Card>
            ))}
            <Space>
              <Button disabled={page <= 1} onClick={() => setPage((value) => value - 1)}>
                上一页
              </Button>
              <span>
                第 {page} 页，共 {total} 项
              </span>
              <Button disabled={page * 50 >= total} onClick={() => setPage((value) => value + 1)}>
                下一页
              </Button>
            </Space>
          </>
        )}
        {detail && (
          <Card title={detail.displayName}>
            <Descriptions
              column={1}
              items={Object.entries(detail.attributes).map(([key, value]) => ({
                key,
                label: FIELD_LABELS[key] ?? key,
                children: display(value),
              }))}
            />
            <Space>
              {(['lineage', 'source', 'native-details'] as const).map((view, index) => (
                <Button key={view} onClick={() => void inspect(detail, view)}>
                  {['资源关系', '源码', '原生详情'][index]}
                </Button>
              ))}
            </Space>
            {trace !== null && (
              <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(trace, null, 2)}</pre>
            )}
          </Card>
        )}
        {result && (
          <Card title={`查询 ${result.queryId} · 版本 ${result.releaseId}`}>
            <p>
              {result.state}
              {result.errorCode && `：${result.errorCode}`}
            </p>
            {result.sql && <pre>{result.sql}</pre>}
            {result.columns && (
              <table>
                <thead>
                  <tr>
                    {result.columns.map((column) => (
                      <th key={column.name}>{column.name}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.rows?.map((row, index) => (
                    <tr key={index}>
                      {row.map((cell, cellIndex) => (
                        <td key={cellIndex}>{display(cell)}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {result.truncated && <p>结果已截断</p>}
          </Card>
        )}
      </Space>
    </Card>
  );
}
