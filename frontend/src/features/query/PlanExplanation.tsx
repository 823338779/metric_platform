import { CopyOutlined } from '@ant-design/icons';
import { Button, Card, List, message, Space, Tag, Typography } from 'antd';
import type { ValidationResponse } from '../../api/types';

export function PlanExplanation({ plan, sql }: { plan: ValidationResponse; sql?: string }) {
  const copySql = async () => {
    if (!sql) return;
    await navigator.clipboard.writeText(sql);
    void message.success('SQL 已复制');
  };

  return (
    <Card size="small" title="查询计划说明" className="query-plan-card">
      {!plan.valid ? (
        <Space direction="vertical">
          {/* 业务校验错误属于查询计划的一部分，集中展示在卡片内。 */}
          {plan.issues.map((issue, index) => (
            <Typography.Text key={`${issue.code}-${issue.objectId ?? index}`} type="danger" strong>
              {issue.message}
            </Typography.Text>
          ))}
        </Space>
      ) : (
        <List
          dataSource={plan.aggregates}
          renderItem={(aggregate, index) => (
            <List.Item>
              <List.Item.Meta
                title={`聚合子查询 ${index + 1}`}
                description={`数据集 ${aggregate.datasetId} · ${aggregate.metricIds.length} 个指标 · ${aggregate.dimensionIds.length} 个维度`}
              />
              {aggregate.relationshipIds.map((id) => (
                <Tag key={id} color="geekblue">
                  关系 {id.slice(0, 8)}
                </Tag>
              ))}
            </List.Item>
          )}
        />
      )}
      {sql && (
        <div className="query-plan-sql">
          <div className="query-plan-sql-header">
            <Typography.Text strong>本次查询 SQL</Typography.Text>
            <Button size="small" icon={<CopyOutlined />} onClick={() => void copySql()}>
              复制 SQL
            </Button>
          </div>
          <pre>{sql}</pre>
        </div>
      )}
    </Card>
  );
}
