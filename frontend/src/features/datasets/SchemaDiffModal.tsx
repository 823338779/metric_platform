import { Alert, List, Modal } from 'antd';
import type { SchemaDiff } from '../../api/types';
export function SchemaDiffModal({
  open,
  diff,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  diff?: SchemaDiff;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <Modal
      open={open}
      title="字段变更检测"
      onCancel={onCancel}
      onOk={onConfirm}
      okButtonProps={{ disabled: !!diff?.blockingImpacts.length }}
    >
      {diff?.blockingImpacts.length ? (
        <Alert type="error" message="存在依赖阻塞" description={diff.blockingImpacts.join('；')} />
      ) : null}
      <List
        header="新增字段"
        dataSource={diff?.added ?? []}
        renderItem={(v) => <List.Item>{v.physicalName}</List.Item>}
      />
      <List
        header="移除字段"
        dataSource={diff?.removed ?? []}
        renderItem={(v) => <List.Item>{v.physicalName}</List.Item>}
      />
      <List
        header="类型变化"
        dataSource={diff?.typeChanged ?? []}
        renderItem={(v) => (
          <List.Item>
            {v.physicalName}: {v.previousType} → {v.currentType}
          </List.Item>
        )}
      />
    </Modal>
  );
}
