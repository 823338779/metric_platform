import { Empty, Space, Table, Tag, Typography } from 'antd';
import { useRef } from 'react';
import type { QueryResponse } from '../../api/types';
export function QueryResultTable({
  result,
  loading,
}: {
  result?: QueryResponse;
  loading: boolean;
}) {
  const resultContainerRef = useRef<HTMLDivElement>(null);
  if (!loading && !result) return <Empty description="选择指标并执行查询" />;
  return (
    <div ref={resultContainerRef} style={{ position: 'relative' }}>
      {result && (
        <div style={{ marginBottom: 16 }}>
          <Space size="small">
            <Typography.Text strong>本次查询</Typography.Text>
            <Tag color="blue">耗时 {result.durationMs} ms</Tag>
          </Space>
          {result.sql && (
            <Typography.Paragraph
              copyable={{ text: result.sql, tooltips: ['复制 SQL', '已复制'] }}
              style={{
                background: '#f6f8fa',
                border: '1px solid #e5e7eb',
                borderRadius: 6,
                fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace',
                margin: '8px 0 0',
                maxHeight: 180,
                overflow: 'auto',
                padding: '12px 36px 12px 12px',
                whiteSpace: 'pre',
              }}
            >
              {result.sql}
            </Typography.Paragraph>
          )}
        </div>
      )}
      <Table
        loading={loading}
        pagination={{
          defaultPageSize: 50,
          pageSizeOptions: [10, 20, 50, 100],
          showSizeChanger: {
            placement: 'topLeft',
            getPopupContainer: () => resultContainerRef.current ?? document.body,
          },
        }}
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
    </div>
  );
}
