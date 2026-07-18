import { Card, List, Tag } from 'antd';
import type { ValidationResponse } from '../../api/types';
export function PlanExplanation({ plan }: { plan: ValidationResponse }) {
  return (
    <Card size="small" title="查询计划说明">
      <List
        dataSource={plan.aggregates}
        renderItem={(a, i) => (
          <List.Item>
            <List.Item.Meta
              title={`聚合子查询 ${i + 1}`}
              description={`数据集 ${a.datasetId} · ${a.metricIds.length} 个指标 · ${a.dimensionIds.length} 个维度`}
            />
            {a.relationshipIds.map((id) => (
              <Tag key={id} color="geekblue">
                关系 {id.slice(0, 8)}
              </Tag>
            ))}
          </List.Item>
        )}
      />
    </Card>
  );
}
