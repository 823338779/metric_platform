import { Input, Switch, Table } from 'antd';
import type { DatasetField } from '../../api/types';
export function FieldTable({
  fields,
  onChange,
}: {
  fields: DatasetField[];
  onChange?: (v: DatasetField[]) => void;
}) {
  const update = (id: string, patch: Partial<DatasetField>) =>
    onChange?.(fields.map((f) => (f.id === id ? { ...f, ...patch } : f)));
  return (
    <Table
      sticky
      rowKey="id"
      pagination={false}
      scroll={{ x: 720, y: 400 }}
      dataSource={fields}
      columns={[
        { title: '物理字段', dataIndex: 'physicalName' },
        {
          title: '展示名称',
          render: (_, f) => (
            <Input
              value={f.displayName}
              disabled={!onChange}
              onChange={(e) => update(f.id, { displayName: e.target.value })}
            />
          ),
        },
        { title: '类型', dataIndex: 'physicalType' },
        {
          title: '启用',
          render: (_, f) => (
            <Switch
              checked={f.selected}
              disabled={!onChange}
              onChange={(v) => update(f.id, { selected: v })}
            />
          ),
        },
      ]}
    />
  );
}
