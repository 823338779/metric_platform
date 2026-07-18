import { Empty, Pagination, Table } from 'antd';
import { useEffect, useState } from 'react';
import type { QueryResponse } from '../../api/types';

export function QueryResultTable({
  result,
  loading,
}: {
  result?: QueryResponse;
  loading: boolean;
}) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  useEffect(() => setPage(1), [result?.queryId]);

  if (!loading && !result) {
    return <Empty className="query-result-empty" description="选择指标并执行查询" />;
  }

  const rows = result?.rows ?? [];
  const pageRows = rows.slice((page - 1) * pageSize, page * pageSize);
  return (
    <div className="query-result-table">
      <div className="query-data-table-scroll">
        <Table
          sticky
          loading={loading}
          pagination={false}
          tableLayout="fixed"
          rowKey="key"
          dataSource={pageRows.map((row, index) => ({
            key: (page - 1) * pageSize + index,
            row,
          }))}
          columns={
            result?.columns.map((column, index) => ({
              width: 180,
              ellipsis: { showTitle: true },
              title: (
                <span>
                  {column.name}
                  <small style={{ color: '#999', marginLeft: 6 }}>{column.type}</small>
                </span>
              ),
              render: (_: unknown, record: { row: unknown[] }) =>
                record.row[index] === null ? (
                  <span style={{ color: '#999' }}>NULL</span>
                ) : (
                  <span title={String(record.row[index])}>{String(record.row[index])}</span>
                ),
            })) ?? []
          }
        />
      </div>
      {rows.length > 0 && (
        <Pagination
          className="query-result-pagination"
          current={page}
          pageSize={pageSize}
          pageSizeOptions={[10, 20, 50, 100]}
          showSizeChanger
          total={rows.length}
          onChange={(nextPage, nextPageSize) => {
            setPage(nextPageSize === pageSize ? nextPage : 1);
            setPageSize(nextPageSize);
          }}
        />
      )}
    </div>
  );
}
