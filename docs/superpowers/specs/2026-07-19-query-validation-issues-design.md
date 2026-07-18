# 查询校验错误展示设计

## 目标

当 `POST /api/v1/queries:validate` 正常返回校验结果但 `valid` 为 `false` 时，在“查询计划说明”卡片中展示响应里的全部 `issues[].message`。

## 交互设计

- 校验成功时，“查询计划说明”继续展示现有查询计划内容。
- 校验失败时，“查询计划说明”逐条展示全部 `issues[].message`，使用红色加粗文字突出显示。
- HTTP 或网络异常继续沿用查询工作台现有的页面顶部错误提示，不改变其文案、位置或处理逻辑。
- 校验失败时，执行查询按钮保持禁用，不发送查询执行请求。

## 修改范围

- 仅调整 Metric Platform 前端的 `PlanExplanation` 展示逻辑。
- 不修改 API 客户端、后端接口、数据结构或测试代码。

## 验证

- 运行 Metric Platform 前端格式检查。
- 运行 TypeScript 与 Vite 构建，确认类型和产物构建正常。
