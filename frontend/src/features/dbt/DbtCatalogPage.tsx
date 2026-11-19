import { Alert, Button, Input, Typography } from 'antd';
import { ApartmentOutlined, FolderOpenOutlined, DownOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtCatalog, DbtResource } from '../../api/types';
import { DbtResourceDetail } from './DbtResourceDetail';
import { ResourceTree } from './ResourceTree';
import './resource-workspace.css';

type CatalogApi = { queryParameter?: 'metricResourceId' } & Pick<
  MetricPlatformApi,
  'getDbtCatalog'
> &
  Partial<MetricPlatformApi>;

const DEFAULT_KIND = 'METRIC';
const PAGE_SIZE = 100;
const OTHER = 'OTHER';
const RESOURCE_PARAM = 'resourceId';
const COPY = {
  title: '资源目录',
  subtitle: '浏览业务定义，追溯指标来源与数据依赖。',
  explorer: '项目资源',
  empty: '选择一个资源开始探索',
  emptyHint: '展开左侧目录，查看资源的定义、血缘与源码。',
  noResources: '该类型暂无资源',
  noMatches: '没有匹配的资源',
  noMatchesHint: '尝试其他关键词，或切换资源类型。',
  search: '搜索资源',
  filters: '资源栏目',
  other: '其他资源',
  retry: '重试',
  browse: '浏览资源类型',
  loading: '读取资源中…',
  pinned: '定义、血缘与源码使用同一固定版本。',
};
const KIND_PARAM = 'kind';
const FILTER_ID = 'catalog-kind-';
const FILTER_PANEL = 'catalog-resources';
const FILTER_KEYS = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
const labels: Record<string, string> = {
  METRIC: '指标',
  DIMENSION: '维度',
  SEMANTIC_MODEL: '语义模型',
  TABLE: '数据表',
  VIEW: '视图',
};
const filters = [
  ...Object.entries(labels).map(([key, label]) => ({ key, label })),
  { key: OTHER, label: COPY.other },
];

/** 目录按版本与筛选独立加载，URL 保存选中资源以支持历史导航和深链接。 */
export function DbtCatalogPage({
  api = metricPlatformApi,
  actions,
}: {
  api?: CatalogApi;
  actions?: ReactNode;
  /** 固定构建的查询能力，不影响目录与源码读取。 */
}) {
  const navigate = useNavigate();
  const { projectId = '', buildId: routeBuildId } = useParams();
  const [params, setParams] = useSearchParams();
  const requestedBuild = routeBuildId ?? params.get('buildId') ?? '';
  const [catalog, setCatalog] = useState<Pick<DbtCatalog, 'buildId' | 'resources' | 'total'>>();
  const selected = params.get(RESOURCE_PARAM) ?? '';
  const setSelected = (id: string) =>
    setParams((current) => {
      const next = new URLSearchParams(current);
      if (id) next.set(RESOURCE_PARAM, id);
      else next.delete(RESOURCE_PARAM);
      return next;
    });
  const [located, setLocated] = useState<DbtResource>();
  const [search, setSearch] = useState('');
  const kind = filters.find((filter) => filter.key === params.get(KIND_PARAM))?.key ?? DEFAULT_KIND;
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [treeWidth, setTreeWidth] = useState(268);
  const splitter = useRef<{ x: number; width: number } | undefined>(undefined);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const changeKind = (value: string) => {
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.delete(RESOURCE_PARAM);
      next.set(KIND_PARAM, value);
      return next;
    });
  };
  useEffect(() => {
    let active = true;
    setCatalog(undefined);
    setError('');
    if (!requestedBuild) {
      setError('缺少发布版本 buildId');
      return () => {
        active = false;
      };
    }
    // 聚合当前类型的所有页后一次显示。其他资源须从完整目录中筛选未知类型。
    setLoading(true);
    const loadCatalog = async () => {
      const resources: DbtResource[] = [];
      for (let page = 0; active; page++) {
        const result = await api.getDbtCatalog(
          projectId,
          requestedBuild,
          search,
          page,
          PAGE_SIZE,
          kind === OTHER ? undefined : kind,
        );
        if (!active) return;
        if (result.buildId !== requestedBuild) {
          setError('目录版本与请求不一致');
          return;
        }
        resources.push(
          ...result.resources.filter((resource) =>
            kind === OTHER ? !Object.hasOwn(labels, resource.kind) : resource.kind === kind,
          ),
        );
        if (!result.resources.length || (page + 1) * result.size >= result.total) break;
      }
      if (active)
        setCatalog({
          buildId: requestedBuild,
          total: resources.length,
          resources,
        });
    };
    void loadCatalog()
      .catch((cause: unknown) => {
        if (active)
          setError(
            cause instanceof ApiProblem && cause.problem.status === 410
              ? '该发布版本仅保留发布记录'
              : '目录读取失败',
          );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [api, projectId, requestedBuild, search, kind, loadAttempt]);
  useEffect(() => {
    setLocated(undefined);
    if (
      !selected ||
      !catalog ||
      catalog.resources.some((resource) => resource.resourceId === selected) ||
      !api.getDbtResource
    )
      return;
    let active = true;
    void api
      .getDbtResource(projectId, selected, requestedBuild)
      .then((resource) => {
        if (!active || resource.resourceId !== selected) return;
        const targetKind = Object.hasOwn(labels, resource.kind) ? resource.kind : OTHER;
        if (targetKind === kind) setLocated(resource);
        else
          setParams(
            (current) => {
              const next = new URLSearchParams(current);
              next.set(KIND_PARAM, targetKind);
              return next;
            },
            { replace: true },
          );
      })
      .catch(() => {
        if (active) setError('资源定位失败，请重试');
      });
    return () => {
      active = false;
    };
  }, [api, catalog, projectId, requestedBuild, selected, kind, setParams]);
  const resources = useMemo(
    () => (catalog ? (located ? [...catalog.resources, located] : catalog.resources) : []),
    [catalog, located],
  );
  const selectedResource = resources.find((resource) => resource.resourceId === selected);
  const detailReady =
    api.getDbtPresentation && api.getDbtSource && api.getDbtLineage && api.previewDbtDataset;
  return (
    <div className="catalog-page">
      {error && (
        <Alert
          type="error"
          title={error}
          action={
            <Button onClick={() => setLoadAttempt((value) => value + 1)}>{COPY.retry}</Button>
          }
        />
      )}
      <div
        className="catalog-workspace"
        style={{ '--explorer-width': `${treeWidth}px` } as CSSProperties}
      >
        <aside className="catalog-explorer">
          {projectId && (
            <Link className="explorer-project" to="/dbt/environments">
              <span>
                <small>项目</small>
                <strong title={projectId}>{projectId}</strong>
              </span>
              <DownOutlined />
            </Link>
          )}
          <div className="catalog-explorer-header">
            <div className="catalog-explorer-title">
              <span>
                <FolderOpenOutlined /> {COPY.explorer}
              </span>
              <span className="resource-muted">{catalog?.total ?? 0}</span>
            </div>
            <Input.Search
              placeholder={COPY.search}
              aria-label={COPY.search}
              allowClear
              onSearch={(value) => {
                setSearch(value);
                setSelected('');
              }}
            />
            <div className="catalog-kind-filters" role="tablist" aria-label={COPY.filters}>
              {filters.map((filter, index) => (
                <button
                  key={filter.key}
                  id={FILTER_ID + filter.key}
                  type="button"
                  role="tab"
                  aria-selected={kind === filter.key}
                  aria-controls={FILTER_PANEL}
                  tabIndex={kind === filter.key ? 0 : -1}
                  onClick={() => changeKind(filter.key)}
                  onKeyDown={(event) => {
                    if (!FILTER_KEYS.includes(event.key)) return;
                    event.preventDefault();
                    const nextIndex =
                      event.key === 'Home'
                        ? 0
                        : event.key === 'End'
                          ? filters.length - 1
                          : (index + (event.key === 'ArrowRight' ? 1 : -1) + filters.length) %
                            filters.length;
                    changeKind(filters[nextIndex].key);
                    document.getElementById(FILTER_ID + filters[nextIndex].key)?.focus();
                  }}
                >
                  {filter.label}
                </button>
              ))}
            </div>
          </div>
          <div
            className="catalog-resource-panel"
            id={FILTER_PANEL}
            role="tabpanel"
            aria-labelledby={FILTER_ID + kind}
            aria-busy={loading}
          >
            <ResourceTree
              loading={loading && !catalog}
              queryParameter={api.queryParameter}
              resources={resources}
              selected={selected}
              onSelect={setSelected}
              projectId={projectId}
              buildId={requestedBuild}
            />
            {catalog?.total === 0 && (
              <p className="catalog-no-results">{search ? COPY.noMatches : COPY.noResources}</p>
            )}
          </div>
          {(actions || (projectId && requestedBuild)) && (
            <div className="catalog-explorer-footer">
              {actions}
              {projectId && requestedBuild && (
                <details className="explorer-release">
                  <summary>发布版本信息</summary>
                  <p title={requestedBuild}>buildId：{requestedBuild}</p>
                </details>
              )}
            </div>
          )}
        </aside>
        <div
          className="catalog-splitter"
          role="separator"
          aria-label="调整资源树宽度"
          aria-orientation="vertical"
          aria-valuemin={220}
          aria-valuemax={480}
          aria-valuenow={treeWidth}
          tabIndex={0}
          onPointerDown={(event) => {
            splitter.current = { x: event.clientX, width: treeWidth };
            event.currentTarget.setPointerCapture(event.pointerId);
          }}
          onPointerMove={(event) => {
            if (splitter.current)
              setTreeWidth(
                Math.max(
                  220,
                  Math.min(480, splitter.current.width + event.clientX - splitter.current.x),
                ),
              );
          }}
          onPointerUp={() => {
            splitter.current = undefined;
          }}
          onPointerCancel={() => {
            splitter.current = undefined;
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
              event.preventDefault();
              setTreeWidth((width) =>
                Math.max(220, Math.min(480, width + (event.key === 'ArrowRight' ? 16 : -16))),
              );
            }
          }}
        />
        {selected && catalog && detailReady ? (
          <DbtResourceDetail
            key={`${catalog.buildId}:${selected}`}
            projectId={projectId}
            buildId={catalog.buildId}
            resourceId={selected}
            tabTitle={selectedResource?.name}
            granted={selectedResource?.granted}
            onSelect={setSelected}
            onClose={() => setSelected('')}
            onExplore={(name) =>
              navigate(
                `/dbt/builds/${encodeURIComponent(requestedBuild)}/query?${new URLSearchParams({ buildId: requestedBuild, [api.queryParameter ?? 'metric']: api.queryParameter ? selected : (resources.find((resource) => resource.resourceId === selected)?.name ?? name), resourceId: selected })}`,
              )
            }
            api={api as MetricPlatformApi}
          />
        ) : (
          <div className="resource-empty">
            <ApartmentOutlined />
            <h3>{search && catalog?.total === 0 ? COPY.noMatches : COPY.empty}</h3>
            <p>{search && catalog?.total === 0 ? COPY.noMatchesHint : COPY.emptyHint}</p>
            <div className="resource-start-actions" aria-label={COPY.browse}>
              {Object.entries(labels)
                .slice(0, 3)
                .map(([key, label]) => (
                  <Button key={key} onClick={() => changeKind(key)}>
                    {label}
                  </Button>
                ))}
            </div>
            <Typography.Text type="secondary">
              {loading ? COPY.loading : COPY.pinned}
            </Typography.Text>
          </div>
        )}
      </div>
    </div>
  );
}
