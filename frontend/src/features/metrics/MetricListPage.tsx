import { Button, Card, Input, Space, Table } from 'antd';
import { useEffect, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { Dataset, Metric, ResourceDeletionImpact } from '../../api/types';
import { DeletionImpactDialog } from '../../components/DeletionImpactDialog';
import { MetricDrawer } from './MetricDrawer';

export function MetricListPage() {
  const [rows, setRows] = useState<Metric[]>([]);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [editing, setEditing] = useState<Metric>();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [deleting, setDeleting] = useState<Metric>();
  const [impact, setImpact] = useState<ResourceDeletionImpact>();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const load = () =>
    Promise.all([
      metricPlatformApi.listMetrics(new URLSearchParams({ q })),
      metricPlatformApi.listDatasets(new URLSearchParams({ size: '200' })),
    ]).then(([metrics, datasetPage]) => {
      setRows(metrics.items);
      setDatasets(datasetPage.items);
    });
  useEffect(() => {
    void load();
  }, [q]);
  return (
    <Card
      title="指标中心"
      extra={
        <Button
          type="primary"
          onClick={() => {
            setEditing(undefined);
            setOpen(true);
          }}
        >
          新建指标
        </Button>
      }
    >
      <Input.Search
        placeholder="搜索指标"
        onSearch={setQ}
        style={{ width: 360, marginBottom: 16 }}
      />
      <Table
        rowKey="id"
        dataSource={rows}
        columns={[
          { title: '显示名称', dataIndex: 'displayName' },
          { title: '编码', dataIndex: 'name' },
          { title: '聚合', dataIndex: 'aggregation' },
          {
            title: '操作',
            render: (_, metric) => (
              <Space>
                <Button
                  onClick={() => {
                    setEditing(metric);
                    setOpen(true);
                  }}
                >
                  编辑
                </Button>
                <Button
                  danger
                  onClick={() => {
                    setDeleting(metric);
                    setImpact(undefined);
                    void metricPlatformApi.metricDeletionImpact(metric.id).then(setImpact);
                  }}
                >
                  删除
                </Button>
              </Space>
            ),
          },
        ]}
      />
      <MetricDrawer
        open={open}
        value={editing}
        datasets={datasets}
        onClose={() => setOpen(false)}
        onSave={async (value) => {
          if (editing)
            await metricPlatformApi.updateMetric(editing.id, {
              datasetFieldId: value.datasetFieldId,
              name: value.name,
              displayName: value.displayName,
              aggregation: value.aggregation,
              aliases: value.aliases ?? [],
              version: editing.version,
            });
          else await metricPlatformApi.createMetric({ ...value, aliases: value.aliases ?? [] });
          setOpen(false);
          await load();
        }}
      />
      <DeletionImpactDialog
        open={Boolean(deleting)}
        resourceName={deleting?.displayName}
        impact={impact}
        confirming={confirmingDelete}
        onCancel={() => {
          setDeleting(undefined);
          setImpact(undefined);
        }}
        onConfirm={() => {
          if (!deleting || !impact) return;
          setConfirmingDelete(true);
          void metricPlatformApi
            .deleteMetric(deleting.id)
            .then(load)
            .then(() => {
              setDeleting(undefined);
              setImpact(undefined);
            })
            .finally(() => setConfirmingDelete(false));
        }}
      />
    </Card>
  );
}
