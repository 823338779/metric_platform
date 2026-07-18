import { Descriptions, Modal } from 'antd';
import type { DeletionImpact } from '../../api/types';
export function DeletionImpactModal({
  open,
  impact,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  impact?: DeletionImpact;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <Modal
      open={open}
      title="确认删除数据集"
      okText="级联删除"
      okButtonProps={{ danger: true }}
      onCancel={onCancel}
      onOk={onConfirm}
    >
      <Descriptions
        column={2}
        items={[
          { key: 'f', label: '字段', children: impact?.fieldCount ?? '-' },
          { key: 'm', label: '指标', children: impact?.metricCount ?? '-' },
          { key: 'd', label: '维度', children: impact?.dimensionCount ?? '-' },
          { key: 'r', label: '关系', children: impact?.relationshipCount ?? '-' },
        ]}
      />
    </Modal>
  );
}
