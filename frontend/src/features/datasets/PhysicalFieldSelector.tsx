import { Button, Input, Space, Table, Typography } from 'antd';
import { useMemo, useState } from 'react';
import type { SourceColumn } from '../../api/types';

export function PhysicalFieldSelector({
  columns,
  loading,
  value = [],
  onChange,
}: {
  columns: SourceColumn[];
  loading: boolean;
  value?: string[];
  onChange?: (value: string[]) => void;
}) {
  const [query, setQuery] = useState('');
  const visibleColumns = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return columns;
    return columns.filter(
      (column) =>
        column.physicalName.toLowerCase().includes(needle) ||
        column.physicalType.toLowerCase().includes(needle),
    );
  }, [columns, query]);

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap style={{ width: '100%', justifyContent: 'space-between', rowGap: 8 }}>
        <Input.Search
          allowClear
          placeholder="搜索字段名称或类型"
          onChange={(event) => setQuery(event.target.value)}
          style={{ width: 320, maxWidth: '100%' }}
        />
        <Space>
          <Typography.Text type="secondary">已选择 {value.length} 个字段</Typography.Text>
          <Button disabled={!value.length} onClick={() => onChange?.([])}>
            清空选择
          </Button>
        </Space>
      </Space>
      <Table<SourceColumn>
        sticky
        rowKey="physicalName"
        loading={loading}
        pagination={false}
        scroll={{ x: 520, y: 400 }}
        dataSource={visibleColumns}
        locale={{ emptyText: loading ? '正在加载字段' : '没有可选字段' }}
        rowSelection={{
          selectedRowKeys: value,
          preserveSelectedRowKeys: true,
          onChange: (keys) => onChange?.(keys.map(String)),
        }}
        columns={[
          { title: '物理字段', dataIndex: 'physicalName' },
          { title: '类型', dataIndex: 'physicalType' },
        ]}
      />
    </Space>
  );
}
