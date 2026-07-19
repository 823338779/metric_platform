import { Alert, List, Modal, Typography } from 'antd';
import type { ResourceDeletionImpact } from '../api/types';

export function DeletionImpactDialog({
  open,
  resourceName,
  impact,
  confirming = false,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  resourceName?: string;
  impact?: ResourceDeletionImpact;
  confirming?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <Modal
      open={open}
      title={`确认删除${resourceName ? `“${resourceName}”` : ''}`}
      okText="确认删除"
      cancelText="取消"
      okButtonProps={{ danger: true, disabled: !impact }}
      confirmLoading={confirming}
      onCancel={onCancel}
      onOk={onConfirm}
    >
      {impact?.warnings.map((warning) => (
        <Alert
          key={warning}
          type="warning"
          showIcon
          message={warning}
          style={{ marginBottom: 12 }}
        />
      ))}
      <Typography.Paragraph>
        删除影响由当前平台定义实时计算。请确认以下依赖后再继续。
      </Typography.Paragraph>
      <List
        loading={!impact}
        locale={{ emptyText: '未发现已登记的依赖资源' }}
        dataSource={impact?.dependencies ?? []}
        renderItem={(dependency) => (
          <List.Item>
            <List.Item.Meta
              title={`${dependency.type}: ${dependency.name}`}
              description={`${dependency.reason} · ${dependency.id}`}
            />
          </List.Item>
        )}
      />
    </Modal>
  );
}
