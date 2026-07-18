import {
  Button,
  Descriptions,
  Empty,
  List,
  Result,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type {
  DataSourceDetail,
  DataSourceSummary,
  Dataset,
  SourceColumn,
  SourceLocation,
  SourcePreview,
} from '../../api/types';
import type { CatalogTreeNode } from './DataSourceTree';
import {
  healthColor,
  healthLabel,
  safeDataSourceError,
  sourceStatusLabel,
} from './dataSourceHelpers';

export function DataSourceWorkspace({
  node,
  source,
  onEdit,
  onDelete,
  onRefresh,
}: {
  node?: CatalogTreeNode;
  source?: DataSourceSummary;
  onEdit: (value: DataSourceDetail) => void;
  onDelete: (value: DataSourceSummary) => void;
  onRefresh: () => void;
}) {
  const [detail, setDetail] = useState<DataSourceDetail>();
  const [fields, setFields] = useState<SourceColumn[]>([]);
  const [preview, setPreview] = useState<SourcePreview>();
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [limit, setLimit] = useState<20 | 50 | 100>(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const location = node?.location;
  const objectSelected = node?.nodeType === 'object' || node?.nodeType === 'field';
  const load = async (signal?: AbortSignal) => {
    if (!source) return;
    setLoading(true);
    setError('');
    try {
      const dataSource = await metricPlatformApi.getDataSource(source.id);
      setDetail(dataSource);
      if (objectSelected && location?.objectName) {
        const objectLocation = location as SourceLocation;
        const [fieldList, previewData, datasetPage] = await Promise.all([
          metricPlatformApi.listSourceFields(source.id, objectLocation, signal),
          metricPlatformApi.previewSourceObject(source.id, { ...objectLocation, limit }, signal),
          metricPlatformApi.listDatasets(
            new URLSearchParams({ dataSourceId: source.id, size: '200' }),
          ),
        ]);
        setFields(fieldList);
        setPreview(previewData);
        setDatasets(
          datasetPage.items.filter(
            (item) =>
              item.objectName === location.objectName &&
              (!location.schemaName || item.schemaName === location.schemaName),
          ),
        );
      } else {
        setFields([]);
        setPreview(undefined);
        setDatasets([]);
      }
    } catch (value) {
      const text = safeDataSourceError(value);
      if (text) setError(text);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [source?.id, node?.key, limit]);
  const previewRows = useMemo(
    () =>
      preview?.rows.map((row, index) =>
        Object.fromEntries([
          ['__key', index],
          ...row.map((value, column) => [String(column), value]),
        ]),
      ) ?? [],
    [preview],
  );
  if (!source) return <Empty description="从左侧选择数据源或对象" />;
  if (error)
    return (
      <Result
        status="warning"
        title="无法加载数据源"
        subTitle={error}
        extra={<Button onClick={() => void load()}>重试</Button>}
      />
    );
  if (loading && !detail) return <Spin />;
  const header = (
    <Space direction="vertical" size={4}>
      <Space>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {objectSelected ? location?.objectName : source.name}
        </Typography.Title>
        {objectSelected && <Tag color="blue">源对象</Tag>}
      </Space>
      <Typography.Text type="secondary">
        {objectSelected
          ? [location?.catalogName, location?.schemaName].filter(Boolean).join(' / ')
          : `${source.databaseType} · ${sourceStatusLabel[source.status]}`}
      </Typography.Text>
    </Space>
  );
  const actions = (
    <Space>
      <Button
        onClick={() =>
          void metricPlatformApi.testDataSource(source.id).then((result) => {
            message[result.successful ? 'success' : 'error'](
              `${result.message}（${result.latencyMs} ms）`,
            );
            onRefresh();
          })
        }
      >
        测试连接
      </Button>
      <Button onClick={() => detail && onEdit(detail)}>编辑连接</Button>
      {source.status === 'ACTIVE' ? (
        <Button
          onClick={() => void metricPlatformApi.deactivateDataSource(source.id).then(onRefresh)}
        >
          停用
        </Button>
      ) : source.status === 'INACTIVE' ? (
        <Button
          onClick={() => void metricPlatformApi.activateDataSource(source.id).then(onRefresh)}
        >
          启用
        </Button>
      ) : null}
      <Button danger disabled={source.status === 'DELETED'} onClick={() => onDelete(source)}>
        删除
      </Button>
    </Space>
  );
  const config = (
    <Descriptions
      column={2}
      bordered
      size="small"
      items={
        detail
          ? [
              { key: 'type', label: '数据库类型', children: detail.databaseType },
              { key: 'url', label: 'JDBC 地址', children: detail.jdbcUrl },
              { key: 'user', label: '用户名', children: detail.username },
              { key: 'password', label: '密码', children: detail.password || '未配置' },
              { key: 'catalog', label: '默认 Catalog', children: detail.defaultCatalog || '-' },
              { key: 'schema', label: '默认 Schema', children: detail.defaultSchema || '-' },
              {
                key: 'status',
                label: '状态',
                children: <Tag>{sourceStatusLabel[detail.status]}</Tag>,
              },
              {
                key: 'health',
                label: '连接健康',
                children: (
                  <Tag color={healthColor[detail.healthStatus]}>
                    {healthLabel[detail.healthStatus]}
                  </Tag>
                ),
              },
            ]
          : []
      }
    />
  );
  const fieldTable = (
    <Table
      rowKey="physicalName"
      size="small"
      pagination={false}
      dataSource={fields}
      columns={[
        { title: '字段', dataIndex: 'physicalName' },
        { title: '物理类型', dataIndex: 'physicalType' },
        { title: 'JDBC 类型', dataIndex: 'jdbcType' },
        { title: '可空', render: (_, field) => (field.nullable ? '是' : '否') },
        { title: '顺序', dataIndex: 'ordinal' },
      ]}
    />
  );
  const dataPreview = (
    <>
      <Space style={{ marginBottom: 12 }}>
        <Select
          value={limit}
          onChange={setLimit}
          options={[20, 50, 100].map((value) => ({
            value: value as 20 | 50 | 100,
            label: `${value} 行`,
          }))}
        />
        <Button onClick={() => void load()}>刷新预览</Button>
        <Typography.Text type="secondary">只读抽样，不执行自定义 SQL</Typography.Text>
      </Space>
      <Table
        rowKey="__key"
        size="small"
        scroll={{ x: 'max-content', y: 360 }}
        pagination={false}
        dataSource={previewRows}
        columns={
          preview?.columns.map((column, index) => ({
            title: (
              <span>
                {column.name}
                <br />
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {column.physicalType}
                </Typography.Text>
              </span>
            ),
            dataIndex: String(index),
            render: (value: unknown) =>
              value === null ? (
                <Typography.Text type="secondary">NULL</Typography.Text>
              ) : (
                String(value)
              ),
          })) ?? []
        }
      />
    </>
  );
  const objectInfo = (
    <Descriptions
      bordered
      size="small"
      column={1}
      items={[
        { key: 'catalog', label: 'Catalog', children: location?.catalogName || '-' },
        { key: 'schema', label: 'Schema', children: location?.schemaName || '-' },
        { key: 'object', label: '对象', children: location?.objectName || '-' },
        { key: 'fields', label: '字段数', children: fields.length },
      ]}
    />
  );
  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: 16,
        }}
      >
        {header}
        {actions}
      </div>
      {objectSelected ? (
        <Tabs
          items={[
            { key: 'preview', label: '数据预览', children: dataPreview },
            { key: 'fields', label: `字段结构 ${fields.length}`, children: fieldTable },
            { key: 'info', label: '对象信息', children: objectInfo },
            {
              key: 'refs',
              label: `引用数据集 ${datasets.length}`,
              children: (
                <List
                  dataSource={datasets}
                  renderItem={(item) => <List.Item>{item.name}</List.Item>}
                />
              ),
            },
            { key: 'config', label: '连接配置', children: config },
          ]}
        />
      ) : (
        config
      )}
    </div>
  );
}
