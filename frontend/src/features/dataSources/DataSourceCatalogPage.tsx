import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Layout, Space, Switch, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { DataSourceDetail, DataSourceSummary } from '../../api/types';
import { DataSourceDeleteModal } from './DataSourceDeleteModal';
import { DataSourceEditorDrawer } from './DataSourceEditorDrawer';
import { DataSourceTree, type CatalogTreeNode } from './DataSourceTree';
import { DataSourceWorkspace } from './DataSourceWorkspace';

export function DataSourceCatalogPage() {
  const [sources, setSources] = useState<DataSourceSummary[]>([]);
  const [showDeleted, setShowDeleted] = useState(false);
  const [selected, setSelected] = useState<CatalogTreeNode>();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<DataSourceDetail>();
  const [deleting, setDeleting] = useState<DataSourceSummary>();
  const load = () =>
    metricPlatformApi
      .listDataSources(showDeleted)
      .then((values) => {
        setSources(values);
        if (selected && !values.some((source) => source.id === selected.sourceId))
          setSelected(undefined);
      })
      .catch(() => message.error('数据源列表加载失败'));
  useEffect(() => {
    void load();
  }, [showDeleted]);
  const source = sources.find((item) => item.id === selected?.sourceId);
  return (
    <Card
      styles={{ body: { padding: 0 } }}
      title={
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            数据源目录
          </Typography.Title>
          <Typography.Text type="secondary">连接、发现并预览源数据</Typography.Text>
        </div>
      }
      extra={
        <Space>
          <span>显示已删除</span>
          <Switch checked={showDeleted} onChange={setShowDeleted} />
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditing(undefined);
              setEditorOpen(true);
            }}
          >
            新建数据源
          </Button>
        </Space>
      }
    >
      <Layout style={{ height: 'calc(100vh - 177px)', minHeight: 600, background: '#fff' }}>
        <Layout.Sider
          width={340}
          theme="light"
          style={{ padding: 16, borderRight: '1px solid #edf0f3', overflow: 'hidden' }}
        >
          <DataSourceTree
            sources={sources}
            selectedKey={selected?.key}
            onSelect={setSelected}
            onError={(text) => message.error(text)}
          />
        </Layout.Sider>
        <Layout.Content style={{ padding: 24, overflow: 'auto' }}>
          <DataSourceWorkspace
            node={selected}
            source={source}
            onEdit={(value) => {
              setEditing(value);
              setEditorOpen(true);
            }}
            onDelete={setDeleting}
            onRefresh={() => void load()}
          />
        </Layout.Content>
      </Layout>
      <DataSourceEditorDrawer
        open={editorOpen}
        value={editing}
        onClose={() => setEditorOpen(false)}
        onSaved={() => {
          setEditorOpen(false);
          void load();
        }}
      />
      <DataSourceDeleteModal
        value={deleting}
        onClose={() => setDeleting(undefined)}
        onDeleted={() => {
          setDeleting(undefined);
          setSelected(undefined);
          void load();
        }}
      />
    </Card>
  );
}
