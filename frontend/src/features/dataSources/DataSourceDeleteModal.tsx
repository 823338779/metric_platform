import { Alert, List, Modal, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { DataSourceDeletionImpact, DataSourceSummary } from '../../api/types';

export function DataSourceDeleteModal({
  value,
  onClose,
  onDeleted,
}: {
  value?: DataSourceSummary;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [impact, setImpact] = useState<DataSourceDeletionImpact>();
  const [deleting, setDeleting] = useState(false);
  useEffect(() => {
    setImpact(undefined);
    if (value) void metricPlatformApi.dataSourceDeletionImpact(value.id).then(setImpact);
  }, [value]);
  return (
    <Modal
      open={!!value}
      title="逻辑删除数据源"
      onCancel={onClose}
      okText="确认删除"
      okButtonProps={{ danger: true, loading: deleting }}
      onOk={() => {
        if (!value) return;
        setDeleting(true);
        void metricPlatformApi
          .deleteDataSource(value.id)
          .then(onDeleted)
          .finally(() => setDeleting(false));
      }}
    >
      <Alert
        type="warning"
        showIcon
        message="已有语义定义将保留，但查询会被禁用"
        style={{ marginBottom: 16 }}
      />
      <Typography.Paragraph>
        删除后数据源配置仍可审计查看，不能再浏览元数据、预览数据或创建数据集。
      </Typography.Paragraph>
      <List
        loading={!impact}
        header={`受影响数据集（${impact?.datasets.length ?? 0}）`}
        dataSource={impact?.datasets ?? []}
        locale={{ emptyText: '没有引用该数据源的数据集' }}
        renderItem={(item) => <List.Item>{item.name}</List.Item>}
      />
    </Modal>
  );
}
