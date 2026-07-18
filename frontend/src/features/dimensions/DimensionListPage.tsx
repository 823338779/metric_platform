import { Button, Card, Input, Space, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { Dataset, Dimension } from '../../api/types';
import { DimensionDrawer } from './DimensionDrawer';

export function DimensionListPage() {
  const [rows, setRows] = useState<Dimension[]>([]);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [editing, setEditing] = useState<Dimension>();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const load = () =>
    Promise.all([
      metricPlatformApi.listDimensions(new URLSearchParams({ q })),
      metricPlatformApi.listDatasets(new URLSearchParams({ size: '200' })),
    ]).then(([dimensions, datasetPage]) => {
      setRows(dimensions.items);
      setDatasets(datasetPage.items);
    });
  useEffect(() => {
    void load();
  }, [q]);
  return (
    <Card
      title="维度中心"
      extra={
        <Button
          type="primary"
          onClick={() => {
            setEditing(undefined);
            setOpen(true);
          }}
        >
          新建维度
        </Button>
      }
    >
      <Input.Search
        placeholder="搜索维度"
        onSearch={setQ}
        style={{ width: 360, marginBottom: 16 }}
      />
      <Table
        rowKey="id"
        dataSource={rows}
        columns={[
          { title: '显示名称', dataIndex: 'displayName' },
          { title: '编码', dataIndex: 'name' },
          {
            title: '类型',
            render: (_, dimension) => (
              <Tag color={dimension.type === 'TIME' ? 'blue' : 'default'}>
                {dimension.type === 'TIME' ? '时间' : '标准'}
              </Tag>
            ),
          },
          {
            title: '时间粒度',
            render: (_, dimension) => dimension.allowedGranularities.join('、') || '-',
          },
          {
            title: '操作',
            render: (_, dimension) => (
              <Space>
                <Button
                  onClick={() => {
                    setEditing(dimension);
                    setOpen(true);
                  }}
                >
                  编辑
                </Button>
                <Button
                  danger
                  onClick={() => void metricPlatformApi.deleteDimension(dimension.id).then(load)}
                >
                  删除
                </Button>
              </Space>
            ),
          },
        ]}
      />
      <DimensionDrawer
        open={open}
        value={editing}
        datasets={datasets}
        onClose={() => setOpen(false)}
        onSave={async (value) => {
          if (editing)
            await metricPlatformApi.updateDimension(editing.id, {
              datasetFieldId: value.datasetFieldId,
              name: value.name,
              displayName: value.displayName,
              type: value.type,
              allowedGranularities: value.allowedGranularities,
              aliases: value.aliases ?? [],
              version: editing.version,
            });
          else await metricPlatformApi.createDimension({ ...value, aliases: value.aliases ?? [] });
          setOpen(false);
          await load();
        }}
      />
    </Card>
  );
}
