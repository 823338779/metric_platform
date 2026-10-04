import { Alert, Button, Card, Input, Space, Tabs, Typography } from 'antd';
import { ApartmentOutlined, FolderOpenOutlined, DownOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtCatalog, DbtRelease, DbtResource } from '../../api/types';
import { DbtResourceDetail } from './DbtResourceDetail';
import { ResourceTree } from './ResourceTree';
import './resource-workspace.css';

type CatalogApi = { queryParameter?: 'metricResourceId' } & Pick<
  MetricPlatformApi,
  'getDbtCatalog'
> &
  Partial<
    Pick<
      MetricPlatformApi,
      | 'listDbtReleases'
      | 'getDbtRelease'
      | 'listDbtProjects'
      | 'getDbtPresentation'
      | 'getDbtSource'
      | 'getDbtLineage'
      | 'previewDbtDataset'
      | 'getDbtQuery'
      | 'getDbtResource'
    >
  >;

const ALL = 'ALL';
const OTHER = 'OTHER';
const RESOURCE_PARAM = 'resourceId';
const COPY = {
  title: '资源目录',
  subtitle: '浏览业务定义，追溯指标来源与数据依赖。',
  explorer: '项目资源',
  empty: '选择一个资源开始探索',
  emptyHint: '展开左侧目录，查看资源的定义、血缘与源码。',
  more: '加载更多资源',
  noResources: '该版本没有可展示资源',
};
const labels: Record<string, string> = {
  METRIC: '指标',
  DIMENSION: '维度',
  SEMANTIC_MODEL: '语义模型',
  TABLE: '数据表',
  VIEW: '视图',
};

/** 目录按版本与筛选独立加载，URL 保存选中资源以支持历史导航和深链接。 */
export function DbtCatalogPage({
  api = metricPlatformApi,
  actions,
}: {
  api?: CatalogApi;
  actions?: ReactNode;
}) {
  const navigate = useNavigate();
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const requestedRelease = params.get('releaseId') ?? '';
  const [catalog, setCatalog] = useState<DbtCatalog>();
  const [releases, setReleases] = useState<DbtRelease[]>([]);
  const [historicalRelease, setHistoricalRelease] = useState<DbtRelease>();
  const [activeReleaseId, setActiveReleaseId] = useState<string | null>();
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
  const kind = params.get('kind') ?? undefined;
  const [page, setPage] = useState(0);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const catalogScope = useRef('');
  const [treeWidth, setTreeWidth] = useState(292);
  const splitter = useRef<{ x: number; width: number } | undefined>(undefined);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const historical = activeReleaseId != null && requestedRelease !== activeReleaseId;
  useEffect(() => {
    if (!api.listDbtProjects) {
      setActiveReleaseId(null);
      return;
    }
    let active = true;
    setActiveReleaseId(undefined);
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
    setHistoricalRelease(undefined);
    if (!historical || !api.getDbtRelease) return;
    let active = true;
    void api
      .getDbtRelease(projectId, requestedRelease)
      .then((release) => {
        if (active && release.releaseId === requestedRelease) setHistoricalRelease(release);
      })
      .catch(() => {
        if (active) setError('发布记录读取失败');
      });
    return () => {
      active = false;
    };
  }, [api, historical, projectId, requestedRelease]);
  useEffect(() => {
    let active = true;
    // 浏览器历史也能改变筛选；新范围必须从第一页读取，不能合并旧范围的资源。
    const scope = JSON.stringify([projectId, requestedRelease, kind, search]);
    if (catalogScope.current !== scope) {
      catalogScope.current = scope;
      setCatalog(undefined);
      if (page !== 0) {
        setPage(0);
        setLoading(false);
        return () => {
          active = false;
        };
      }
    }
    if (page === 0) setCatalog(undefined);
    setError('');
    if (!requestedRelease) {
      setError('缺少发布版本 releaseId');
      return () => {
        active = false;
      };
    }
    if (activeReleaseId === undefined || historical)
      return () => {
        active = false;
      };
    // 常见类型在服务端分页；其他资源遍历当前版本后筛选，避免漏掉后续页。
    setLoading(true);
    const loadCatalog = async () => {
      if (kind !== OTHER)
        return api.getDbtCatalog(projectId, requestedRelease, search, page, 20, kind);
      const resources: DbtResource[] = [];
      for (let index = 0; active; index++) {
        const value = await api.getDbtCatalog(projectId, requestedRelease, search, index, 100);
        if (value.releaseId !== requestedRelease) throw new Error('目录版本与请求不一致');
        resources.push(...value.resources.filter((resource) => !labels[resource.kind]));
        if (value.resources.length < 100 || (index + 1) * 100 >= value.total) break;
      }
      return {
        releaseId: requestedRelease,
        page,
        size: 20,
        total: resources.length,
        resources: resources.slice(page * 20, (page + 1) * 20),
      };
    };
    void loadCatalog()
      .then((value) => {
        if (active && value.releaseId === requestedRelease)
          setCatalog((previous) => ({
            ...value,
            resources:
              page > 0 && previous?.releaseId === value.releaseId
                ? [
                    ...new Map(
                      [...previous.resources, ...value.resources].map((resource) => [
                        resource.resourceId,
                        resource,
                      ]),
                    ).values(),
                  ]
                : value.resources,
          }));
        else if (active) setError('目录版本与请求不一致');
      })
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
  }, [
    api,
    projectId,
    requestedRelease,
    search,
    page,
    kind,
    activeReleaseId,
    historical,
    loadAttempt,
  ]);
  useEffect(() => {
    if (!api.listDbtReleases) return;
    let active = true;
    void api
      .listDbtReleases(projectId)
      .then((value) => {
        if (active) setReleases(value);
      })
      .catch(() => {
        if (active) setReleases([]);
      });
    return () => {
      active = false;
    };
  }, [api, projectId]);
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
      .getDbtResource(projectId, selected, requestedRelease)
      .then((resource) => {
        if (active && resource.resourceId === selected) setLocated(resource);
      })
      .catch(() => {
        if (active) setError('资源定位失败，请重试');
      });
    return () => {
      active = false;
    };
  }, [api, catalog, projectId, requestedRelease, selected]);
  const release = historicalRelease ?? releases.find((item) => item.releaseId === requestedRelease);
  const resources = useMemo(
    () => (catalog ? (located ? [...catalog.resources, located] : catalog.resources) : []),
    [catalog, located],
  );
  const detailReady =
    api.getDbtPresentation &&
    api.getDbtSource &&
    api.getDbtLineage &&
    api.previewDbtDataset &&
    api.getDbtQuery;
  if (historical)
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert type="info" title="该发布版本仅保留发布记录" />
        {error && <Alert type="error" title={error} />}
        <Card title="历史发布">
          <Typography.Text>releaseId：{requestedRelease}</Typography.Text>
          {release && (
            <>
              <Typography.Paragraph>发布提交：{release.targetCommitSha}</Typography.Paragraph>
              <Typography.Paragraph>发布状态：{release.state}</Typography.Paragraph>
              <Typography.Paragraph>清理状态：{release.cleanupState}</Typography.Paragraph>
            </>
          )}
          <Link
            to={`/dbt/projects/${encodeURIComponent(projectId)}/catalog?${new URLSearchParams({ releaseId: activeReleaseId, ...(params.get('branchId') ? { branchId: params.get('branchId')! } : {}) })}`}
          >
            查看当前发布版本
          </Link>
        </Card>
      </Space>
    );
  return (
    <div className="catalog-page">
      {error && <Alert type="error" title={error} />}
      <div
        className="catalog-workspace"
        style={{ '--explorer-width': `${treeWidth}px` } as CSSProperties}
      >
        <aside className="catalog-explorer">
          <Link className="explorer-project" to="/dbt/projects">
            <span>
              <small>项目</small>
              <strong title={projectId}>{projectId}</strong>
            </span>
            <DownOutlined />
          </Link>
          <div className="catalog-explorer-header">
            <div className="catalog-explorer-title">
              <span>
                <FolderOpenOutlined /> {COPY.explorer}
              </span>
              <span className="resource-muted">{catalog?.total ?? 0}</span>
            </div>
            <Input.Search
              placeholder="搜索资源"
              allowClear
              onSearch={(value) => {
                setPage(0);
                setSearch(value);
                setSelected('');
              }}
            />
            <Tabs
              aria-label="资源栏目"
              activeKey={kind ?? ALL}
              onChange={(value) => {
                setPage(0);
                setParams((current) => {
                  const next = new URLSearchParams(current);
                  next.delete(RESOURCE_PARAM);
                  if (value === ALL) next.delete('kind');
                  else next.set('kind', value);
                  return next;
                });
              }}
              items={[
                { key: ALL, label: '全部资源' },
                ...Object.entries(labels).map(([key, label]) => ({ key, label })),
                { key: OTHER, label: '其他资源' },
              ]}
            />
          </div>
          <ResourceTree
            queryParameter={api.queryParameter}
            branchId={params.get('branchId') ?? undefined}
            resources={resources}
            selected={selected}
            onSelect={setSelected}
            projectId={projectId}
            releaseId={requestedRelease}
          />
          <div className="catalog-explorer-footer">
            {actions}
            {requestedRelease && (
              <details className="explorer-release">
                <summary>发布版本信息</summary>
                <p title={requestedRelease}>releaseId：{requestedRelease}</p>
                {release && <p>发布提交：{release.targetCommitSha}</p>}
                {release && <p>发布时间：{release.publishedAt ?? '尚未发布'}</p>}
              </details>
            )}
            <span>
              已加载 {catalog?.resources.length ?? 0} / {catalog?.total ?? 0} 项
            </span>
            {catalog && catalog.resources.length < catalog.total && (
              <Button
                loading={loading}
                onClick={() => {
                  setPage(catalog.page + 1);
                  setLoadAttempt((value) => value + 1);
                }}
              >
                {COPY.more}
              </Button>
            )}
            {catalog?.total === 0 && (
              <Typography.Text type="secondary">{COPY.noResources}</Typography.Text>
            )}
          </div>
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
            key={`${catalog.releaseId}:${selected}`}
            projectId={projectId}
            releaseId={catalog.releaseId}
            resourceId={selected}
            tabTitle={resources.find((resource) => resource.resourceId === selected)?.name}
            onSelect={setSelected}
            onClose={() => setSelected('')}
            onExplore={(name) =>
              navigate(
                `/dbt/projects/${encodeURIComponent(projectId)}/query?${new URLSearchParams({ releaseId: requestedRelease, ...(params.get('branchId') ? { branchId: params.get('branchId')! } : {}), [api.queryParameter ?? 'metric']: api.queryParameter ? selected : (resources.find((resource) => resource.resourceId === selected)?.name ?? name), resourceId: selected })}`,
              )
            }
            api={api as MetricPlatformApi}
          />
        ) : (
          <div className="resource-empty">
            <ApartmentOutlined />
            <h3>{COPY.empty}</h3>
            <p>{COPY.emptyHint}</p>
            {loading && <Typography.Text type="secondary">读取中…</Typography.Text>}
          </div>
        )}
      </div>
    </div>
  );
}
