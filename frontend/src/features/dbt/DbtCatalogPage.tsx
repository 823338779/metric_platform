import { Alert, Card, Descriptions, Input, Space, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtCatalog, DbtLineage, DbtResource } from '../../api/types';

type CatalogApi = Pick<MetricPlatformApi, 'getDbtCatalog'> &
  Partial<Pick<MetricPlatformApi, 'getDbtResource' | 'getDbtLineage'>>;

/** 所有资源详情和血缘沿用目录响应中的 releaseId。 */
export function DbtCatalogPage({ api = metricPlatformApi }: { api?: CatalogApi }) {
  const { projectId = '' } = useParams();
  const [params] = useSearchParams();
  const requestedRelease = params.get('releaseId') ?? undefined;
  const [catalog, setCatalog] = useState<DbtCatalog>();
  const [selected, setSelected] = useState<DbtResource>();
  const [lineage, setLineage] = useState<DbtLineage>();
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    void api
      .getDbtCatalog(projectId, requestedRelease, search)
      .then(setCatalog)
      .catch((cause: unknown) =>
        setError(
          cause instanceof ApiProblem && cause.problem.status === 410
            ? '该发布版本已清理'
            : '目录读取失败',
        ),
      );
  }, [api, projectId, requestedRelease, search]);
  const select = (resource: DbtResource) => {
    setSelected(resource);
    setLineage(undefined);
    if (catalog && api.getDbtLineage)
      void api.getDbtLineage(projectId, resource.resourceId, catalog.releaseId).then(setLineage);
  };
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {error && <Alert type="error" title={error} />}
      <Card
        title="dbt 只读目录"
        extra={<Typography.Text>releaseId：{catalog?.releaseId ?? '读取中'}</Typography.Text>}
      >
        <Input.Search placeholder="搜索资源" onSearch={setSearch} style={{ marginBottom: 16 }} />
        <Table
          rowKey="resourceId"
          dataSource={catalog?.resources ?? []}
          pagination={false}
          columns={[
            {
              title: '名称',
              dataIndex: 'name',
              render: (name: string, row: DbtResource) => <a onClick={() => select(row)}>{name}</a>,
            },
            { title: '类型', dataIndex: 'kind' },
            { title: '定义来源', dataIndex: 'sourcePath' },
            {
              title: '查询',
              render: (_, row) =>
                row.queryable && catalog ? (
                  <Link
                    to={`/dbt/projects/${encodeURIComponent(projectId)}/query?releaseId=${catalog.releaseId}&metric=${encodeURIComponent(row.name)}`}
                  >
                    固定版本查询
                  </Link>
                ) : null,
            },
          ]}
        />
      </Card>
      {selected && (
        <Card title={selected.name}>
          <Descriptions
            items={[
              { key: 'source', label: '定义来源', children: selected.sourcePath ?? '未知' },
              { key: 'native', label: '原生 ID', children: selected.nativeId },
              {
                key: 'definition',
                label: '原生定义',
                children: <pre>{selected.definitionJson}</pre>,
              },
              {
                key: 'lineage',
                label: '血缘',
                children:
                  lineage?.dependencies
                    .map((edge) => `${edge.upstreamResourceId} → ${edge.downstreamResourceId}`)
                    .join('，') ?? '读取中',
              },
            ]}
          />
        </Card>
      )}
    </Space>
  );
}
