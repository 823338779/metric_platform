import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Card, Empty, Input, Space, Table } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { Dataset, DeletionImpact } from '../../api/types';
import { DeletionImpactModal } from './DeletionImpactModal';
export function DatasetListPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const [rows, setRows] = useState<Dataset[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState<Dataset>();
  const [impact, setImpact] = useState<DeletionImpact>();
  const load = () => {
    setLoading(true);
    metricPlatformApi
      .listDatasets(params)
      .then((v) => setRows(v.items ?? []))
      .finally(() => setLoading(false));
  };
  useEffect(load, [params.toString()]);
  const inspect = async (d: Dataset) => {
    setDeleting(d);
    setImpact(await metricPlatformApi.deletionImpact(d.id));
  };
  return (
    <Card
      title="数据集中心"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/datasets/new')}>
          新建数据集
        </Button>
      }
    >
      <Input.Search
        allowClear
        placeholder="搜索数据集"
        defaultValue={params.get('q') ?? ''}
        onSearch={(q) =>
          setParams((p) => {
            q ? p.set('q', q) : p.delete('q');
            return p;
          })
        }
        style={{ maxWidth: 360, marginBottom: 16 }}
      />
      <Table
        loading={loading}
        locale={{
          emptyText: (
            <Empty description="暂无数据集">
              <Button onClick={() => navigate('/datasets/new')}>创建第一个数据集</Button>
            </Empty>
          ),
        }}
        rowKey="id"
        dataSource={rows}
        onRow={(record) => ({ onClick: () => navigate(`/datasets/${record.id}`) })}
        columns={[
          { title: '名称', dataIndex: 'name' },
          { title: '来源对象', render: (_, d) => `${d.schemaName}.${d.objectName}` },
          { title: '类型', dataIndex: 'objectType' },
          {
            title: '操作',
            render: (_, d) => (
              <Space onClick={(e) => e.stopPropagation()}>
                <Button
                  icon={<EditOutlined />}
                  onClick={() => navigate(`/datasets/${d.id}/edit`)}
                />
                <Button danger icon={<DeleteOutlined />} onClick={() => void inspect(d)} />
              </Space>
            ),
          },
        ]}
        pagination={{ total: rows.length }}
      />
      <DeletionImpactModal
        open={!!deleting}
        impact={impact}
        onCancel={() => setDeleting(undefined)}
        onConfirm={() => {
          if (deleting)
            void metricPlatformApi.deleteDataset(deleting.id, true).then(() => {
              setDeleting(undefined);
              load();
            });
        }}
      />
    </Card>
  );
}
