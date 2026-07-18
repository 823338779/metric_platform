import { Empty, Table } from 'antd';
import type { QueryResponse } from '../../api/types';
export function QueryResultTable({
  result,
  loading,
}: {
  result?: QueryResponse;
  loading: boolean;
}) {
  if (!loading && !result) return <Empty description="选择指标并执行查询" />;
  return (
    <Table
      loading={loading}
      pagination={{ pageSize: 50 }}
      rowKey={(_, i) => String(i)}
      dataSource={result?.rows.map((row, index) => ({ key: index, row })) ?? []}
      columns={
        result?.columns.map((c, index) => ({
          title: (
            <span>
              {c.name}
              <small style={{ color: '#999', marginLeft: 6 }}>{c.type}</small>
            </span>
          ),
          render: (_: unknown, r: { row: unknown[] }) =>
            r.row[index] === null ? (
              <span style={{ color: '#999' }}>NULL</span>
            ) : (
              String(r.row[index])
            ),
        })) ?? []
      }
    />
  );
}
