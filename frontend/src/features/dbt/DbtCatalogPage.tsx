import { Alert, Card, Input, Space, Table, Tabs, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtCatalog, DbtRelease, DbtResource } from '../../api/types';
import { DbtResourceDetail } from './DbtResourceDetail';

type CatalogApi = Pick<MetricPlatformApi, 'getDbtCatalog'> &
  Partial<
    Pick<
      MetricPlatformApi,
      | 'listDbtReleases'
      | 'getDbtPresentation'
      | 'getDbtSource'
      | 'getDbtLineage'
      | 'previewDbtDataset'
      | 'getDbtQuery'
    >
  >;

const ALL = 'ALL';
const OTHER = 'OTHER';
const labels: Record<string, string> = {
  METRIC: '指标',
  DIMENSION: '维度',
  SEMANTIC_MODEL: '语义模型',
  TABLE: '数据表',
  VIEW: '视图',
};

/** 版本切换立即清空目录和选择，所有后续请求固定到本次响应的 releaseId。 */
export function DbtCatalogPage({ api = metricPlatformApi }: { api?: CatalogApi }) {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const requestedRelease = params.get('releaseId') ?? '';
  const [catalog, setCatalog] = useState<DbtCatalog>();
  const [releases, setReleases] = useState<DbtRelease[]>([]);
  const [selected, setSelected] = useState('');
  const [search, setSearch] = useState('');
  const kind = params.get('kind') ?? undefined;
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setCatalog(undefined);
    setSelected('');
    setError('');
    if (!requestedRelease) {
      setError('缺少发布版本 releaseId');
      return () => {
        active = false;
      };
    }
    // 常见类型在服务端分页；其他资源遍历当前版本后筛选，避免漏掉后续页。
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
        if (active && value.releaseId === requestedRelease) setCatalog(value);
        else if (active) setError('目录版本与请求不一致');
      })
      .catch((cause: unknown) => {
        if (active)
          setError(
            cause instanceof ApiProblem && cause.problem.status === 410
              ? '该发布版本已清理'
              : '目录读取失败',
          );
      });
    return () => {
      active = false;
    };
  }, [api, projectId, requestedRelease, search, page, kind]);
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
  const release = releases.find((item) => item.releaseId === catalog?.releaseId);
  const resources = catalog?.resources ?? [];
  const detailReady =
    api.getDbtPresentation &&
    api.getDbtSource &&
    api.getDbtLineage &&
    api.previewDbtDataset &&
    api.getDbtQuery;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {error && <Alert type="error" title={error} />}
      <Card
        title="资源目录"
        extra={
          <Typography.Text>
            releaseId：{catalog?.releaseId ?? requestedRelease ?? '读取中'}
          </Typography.Text>
        }
      >
        {release && (
          <Space direction="vertical" style={{ marginBottom: 16 }}>
            <Typography.Text>发布提交：{release.targetCommitSha}</Typography.Text>
            <Typography.Text>构建来源：{release.buildSourceSha}</Typography.Text>
            <Typography.Text>发布时间：{release.publishedAt ?? '尚未发布'}</Typography.Text>
          </Space>
        )}
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="搜索资源"
            onSearch={(value) => {
              setPage(0);
              setSearch(value);
            }}
          />
        </Space>
        <Tabs
          aria-label="资源栏目"
          activeKey={kind ?? ALL}
          onChange={(value) => {
            setPage(0);
            setSelected('');
            setParams((current) => {
              const next = new URLSearchParams(current);
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
        <Typography.Paragraph type="secondary">
          {kind === 'METRIC'
            ? '从指标进入探索数据，选择维度与日期范围，即时查看图表。'
            : '按栏目浏览当前版本的资源，点击名称查看定义、来源与血缘。'}
        </Typography.Paragraph>
        <Table
          rowKey="resourceId"
          dataSource={resources}
          pagination={{
            current: page + 1,
            pageSize: 20,
            total: catalog?.total ?? 0,
            onChange: (next) => setPage(next - 1),
          }}
          columns={[
            {
              title: '名称',
              dataIndex: 'name',
              render: (name: string, row: DbtResource) => (
                <a onClick={() => setSelected(row.resourceId)}>{name}</a>
              ),
            },
            {
              title: '类型',
              dataIndex: 'kind',
              render: (value: string) => <Tag>{labels[value] ?? value}</Tag>,
            },
            { title: '说明', dataIndex: 'description', ellipsis: true },
            { title: '定义来源', dataIndex: 'sourcePath' },
            {
              title: '查询',
              render: (_: unknown, row: DbtResource) =>
                row.queryable && row.kind === 'METRIC' && catalog ? (
                  <Link
                    to={`/dbt/projects/${encodeURIComponent(projectId)}/query?releaseId=${catalog.releaseId}&metric=${encodeURIComponent(row.name)}`}
                  >
                    探索数据
                  </Link>
                ) : row.kind === 'METRIC' ? (
                  <Typography.Text type="secondary">当前版本不可查询</Typography.Text>
                ) : null,
            },
          ]}
        />
        {catalog?.total === 0 && (
          <Typography.Text type="secondary">该版本没有可展示资源</Typography.Text>
        )}
      </Card>
      {selected && catalog && detailReady && (
        <DbtResourceDetail
          key={`${catalog.releaseId}:${selected}`}
          projectId={projectId}
          releaseId={catalog.releaseId}
          resourceId={selected}
          onSelect={setSelected}
          api={api as MetricPlatformApi}
        />
      )}
    </Space>
  );
}
