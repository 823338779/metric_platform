# Query Validation Issues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在“查询计划说明”卡片中以红色加粗文字展示查询校验响应里的全部 `issues[].message`。

**Architecture:** 保持查询工作台现有请求、状态和 HTTP/网络异常处理不变。仅让 `PlanExplanation` 根据 `ValidationResponse.valid` 选择展示成功计划或业务校验错误。

**Tech Stack:** React 19、TypeScript、Ant Design 6、Vite 7

## Global Constraints

- HTTP 或网络异常继续沿用现有效果，不修改其文案、位置或处理逻辑。
- 仅展示业务校验响应中的全部 `issues[].message`。
- 错误信息必须在“查询计划说明”卡片中以红色加粗文字逐条展示。
- 不新增、删除或修改测试代码。
- 不创建 Git 提交。

---

### Task 1: 在查询计划卡片中展示业务校验错误

**Files:**
- Modify: `frontend/src/features/query/PlanExplanation.tsx:1-24`

**Interfaces:**
- Consumes: `ValidationResponse` 的 `valid: boolean`、`issues: ValidationIssue[]` 与每个 issue 的 `message: string`。
- Produces: `PlanExplanation({ plan }: { plan: ValidationResponse })`；组件签名不变。

- [ ] **Step 1: 修改查询计划说明的条件渲染**

将 `PlanExplanation` 改为：`plan.valid` 为 `false` 时，在原卡片内逐条渲染红色加粗错误；否则保留现有查询计划列表。

```tsx
import { Card, List, Space, Tag, Typography } from 'antd';
import type { ValidationResponse } from '../../api/types';
export function PlanExplanation({ plan }: { plan: ValidationResponse }) {
  return (
    <Card size="small" title="查询计划说明">
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
    </Card>
  );
}
```

- [ ] **Step 2: 运行格式检查**

Run: `npm.cmd --prefix .\frontend run format:check`

Expected: Prettier 输出 `All matched files use Prettier code style!` 并以退出码 0 结束。

- [ ] **Step 3: 运行前端构建验证**

Run: `npm.cmd --prefix .\frontend run build`

Expected: TypeScript 编译和 Vite 构建均成功，命令以退出码 0 结束。
