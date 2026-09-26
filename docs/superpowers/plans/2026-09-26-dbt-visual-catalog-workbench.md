# dbt 可视化目录与查询工作台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用可交互的 dbt 资源目录和固定版本查询工作台取代原始 JSON 与手填指标、维度名称。

**Architecture:** 前端只消费 Metric Platform 类型化 API；目录详情由后端按已发布 dbt 产物临时转换，页面不解析 YAML 语义，也不持久化展示状态。查询组合、维度值和 `metric_time` 粒度选项来自固定 release 的运行服务能力。

**Tech Stack:** React 19、TypeScript、Ant Design 6、ECharts 6.1、Vitest、Testing Library、Playwright。

**Spec:** `../../../../docs/superpowers/specs/2026-09-26-dbt-platform-modules-and-visual-catalog-design.md`

## Global Constraints

- 只修改 `metric-platform/frontend/`；后端 API 与 OpenAPI 在前一实施计划中提供，不恢复指标、维度或数据源编辑。
- 所有目录、资源、来源、查询选项、维度值与查询都固定 `releaseId`；切换版本不得混用旧数据。
- `metric_time` 是独立时间维度；粒度和原生 group-by token 由 API 返回，前端不拼接 MetricFlow 命名规则。
- 维度值查询有界；查询结果保留 Decimal/大整数的字符串精度，不为绘图而改写表格值。
- StarRocks 无查询能力时明确禁用查询，PostgreSQL profile 作为本轮端到端验收环境。
- 只新增 `echarts` 一个图表依赖，按需导入 graph/line/bar 组件；不新增 React 封装库、第二套状态管理或拖拽编辑器。
- 按工作区 `AGENTS.md` 写中文界面文案和必要注释；不删除、弱化现有测试。整份计划完成并验证后再创建一个代码 commit。

## Review Focus

1. URL 的 `releaseId` 与目录响应不同，或用户切换发布版本：旧资源、查询选项和结果必须清空；Task 2/4 测试覆盖。
2. 多指标组合没有共同维度、日期粒度不可用或维度值截断：页面明确提示并禁止提交无效 token；Task 4 测试覆盖。
3. 已清理版本、服务缺少能力或任务失败：展示 410/能力原因/错误码，不自动回退到当前版本；Task 2/4 测试覆盖。
4. 高精度 Decimal、大整数或不适合绘图的结果：表格保留原值，仅合适结果可绘图；Task 5 测试覆盖。
5. 源码路径或原生定义含 HTML/脚本：页面按文本显示，不执行、不把源码当 HTML 注入；Task 3 测试覆盖。

---

## File Map

| 文件 | 职责 |
| --- | --- |
| `frontend/src/api/types.ts`、`metricPlatformApi.ts` | 展示详情、源码、查询选项、维度值的类型与客户端 |
| `frontend/src/features/dbt/DbtCatalogPage.tsx` | 固定版本目录、搜索和资源分类 |
| `frontend/src/features/dbt/DbtResourceDetail.tsx` | 指标、维度、语义模型、table/view 的类型化详情与源码页签 |
| `frontend/src/features/dbt/DbtLineageGraph.tsx` | 可点击的版本血缘图 |
| `frontend/src/features/dbt/DbtQueryPage.tsx` | 多指标、兼容维度、时间粒度、过滤、任务提交与状态 |
| `frontend/src/features/dbt/DbtDimensionValues.tsx` | 有界维度值读取及截断提示 |
| `frontend/src/features/dbt/DbtQueryResults.tsx` | 保精度表格与适用时的折线/柱状图 |
| `frontend/src/features/dbt/*.test.tsx`、`frontend/e2e/` | 组件行为及浏览器端到端验收 |
| `frontend/package.json`、`frontend/package-lock.json` | ECharts 依赖及可重复安装 |

### Task 1: 类型化前端契约

**Files:** Modify `frontend/src/api/types.ts`, `metricPlatformApi.ts`; create `frontend/src/api/metricPlatformApi.test.ts`。

**Interfaces:** `DbtResourcePresentation` 包含 `releaseId/resourceId/kind/title/sections/nativeDetails/queryable`；`DbtQueryOptions` 包含 `metrics/dimensions/timeDimensions`，每个维度或粒度有 API 返回的 `token`；新增 `getDbtPresentation`、`getDbtSource`、`getDbtQueryOptions`、`submitDbtDimensionValues` 方法。

- [ ] **Step 1: 写失败测试。** `queryOptionsEncodesMultipleMetricsAndRelease`、`presentationAndSourceUseSameRelease`、`dimensionValuesBodyContainsFixedReleaseAndTypedFilters`。
- [ ] **Step 2: 验证测试失败。** 在 `frontend/` 运行 `npm test -- --run src/api/metricPlatformApi.test.ts`，预期方法尚不存在。
- [ ] **Step 3: 实现类型和客户端。** 以 `api/openapi.yaml` 为字段名和错误类型依据；`releaseId` 明确必传，不提供隐式当前版本回退。
- [ ] **Step 4: 验证通过。** 重跑 API 测试与 `npm run build`。

### Task 2: 版本化资源目录

**Files:** Modify `frontend/src/features/dbt/DbtCatalogPage.tsx`, `DbtProjectsPage.tsx`, `DbtPages.test.tsx`；create `DbtCatalogPage.test.tsx`。

**Interfaces:** 目录页 URL 使用 `?releaseId=…`；资源分类为指标、维度、语义模型、dbt model、table/view；资源点击进入同页详情，查询入口携带当前 release。

- [ ] **Step 1: 写失败测试。** `catalogGroupsNativeResourcesAndSearchesWithinPinnedRelease`、`switchingReleaseClearsSelectedResourceAndOldResults`、`cleanedReleaseShows410WithoutFallback`。
- [ ] **Step 2: 验证测试失败。** 运行 `npm test -- --run src/features/dbt/DbtCatalogPage.test.tsx`。
- [ ] **Step 3: 实现目录。** 保持搜索和分页有界；顶部展示发布与构建来源 SHA、构建时间；空状态、加载状态、已清理状态有明确文案。
- [ ] **Step 4: 验证通过。** 重跑本任务测试及现有 `DbtPages.test.tsx`。

### Task 3: 资源详情、YAML 源码与血缘

**Files:** Create `frontend/src/features/dbt/DbtResourceDetail.tsx`, `DbtLineageGraph.tsx`, `DbtResourceDetail.test.tsx`；modify `DbtCatalogPage.tsx`, `package.json`, `package-lock.json`。

**Interfaces:** `DbtResourceDetail` 根据类型化 `sections` 渲染卡片、字段、口径、来源和 YAML 次级页签；`DbtLineageGraph` 接收同 release 的节点/边及 `onSelect(resourceId)`。table/view 详情调用现有 `previewDbtDataset`。

- [ ] **Step 1: 写失败测试。** `rendersMetricDimensionSemanticModelAndPhysicalRelation`、`yamlAndUnknownFieldsAreTextOnly`、`lineageClickKeepsRelease`、`nonQueryableTableHidesPreview`。
- [ ] **Step 2: 验证测试失败。** 运行 `npm test -- --run src/features/dbt/DbtResourceDetail.test.tsx`。
- [ ] **Step 3: 实现详情与血缘。** 使用 Ant Design 卡片/页签及按需导入的 ECharts graph；依赖版本锁定为 6.1.0，图表实例随组件卸载释放；不把 YAML 或原生字段插入 `innerHTML`。
- [ ] **Step 4: 验证通过。** 重跑本任务测试和 `npm run build`，浏览器检查窄屏布局与可点击血缘节点。

### Task 4: 指标组合、日期粒度与维度值

**Files:** Modify `frontend/src/features/dbt/DbtQueryPage.tsx`；create `DbtDimensionValues.tsx`, `DbtQueryPage.test.tsx`。

**Interfaces:** 选择多个指标后 `getDbtQueryOptions(projectId, releaseId, metrics)` 刷新共同维度；时间控件使用返回的 `timeDimension.token` 与 `grain.token`；`DbtDimensionValues` 提交并轮询 `DIMENSION_VALUES` 任务，值用于类型化过滤。

- [ ] **Step 1: 写失败测试。** `multiMetricSelectionReloadsCommonDimensions`、`metricTimeGrainUsesReturnedToken`、`dimensionValuesShowTruncationAndCanFilter`、`releaseSwitchClearsOptionsAndPendingQuery`、`unavailableCapabilityDisablesQuery`。
- [ ] **Step 2: 验证测试失败。** 运行 `npm test -- --run src/features/dbt/DbtQueryPage.test.tsx`。
- [ ] **Step 3: 实现查询构造器。** 过滤字段和操作符来自 API 允许项；日期范围、排序、行数与解释可配置；提交只发送类型化参数及固定 release，取消过期请求或忽略过期响应。
- [ ] **Step 4: 验证通过。** 重跑新旧 dbt 页面测试；手动检查多指标无共同维度、410 与任务失败提示。

### Task 5: 查询结果、图表与浏览器验收

**Files:** Create `frontend/src/features/dbt/DbtQueryResults.tsx`, `DbtQueryResults.test.tsx`, `frontend/e2e/dbt-workbench.spec.ts`, `frontend/e2e/dbt-postgres-integration.spec.ts`；modify `DbtQueryPage.tsx` 与项目根 `README.md` 的前端部分。

**Interfaces:** `DbtQueryResults` 接收 `DbtQueryStatus`，保精度表格永远可用；仅当维度列和数值列均可解释时提供折线/柱状切换。ECharts 只用于图形展示，提交参数不受图形状态影响。

- [ ] **Step 1: 写失败测试。** `decimalAndBigintRemainExactInTable`、`unsupportedShapeOnlyShowsTable`、`timeSeriesOffersLineChart`、`queryFailureKeepsErrorCode`；mock 浏览器测试从目录进入详情再查询固定 release；真实 PostgreSQL 浏览器测试在已启动平台和运行服务、已发布 fixture 项目上查询指标、维度值与月粒度，不拦截 API。仅在 `DBT_POSTGRES_E2E=1` 时运行真实集成测试，未启用时明确跳过。
- [ ] **Step 2: 验证测试失败。** 运行 `npm test -- --run src/features/dbt/DbtQueryResults.test.tsx`，预期组件不存在。
- [ ] **Step 3: 实现结果视图。** 图表只消费可安全转为数值的副本，不改变 API 原始值；释放图表实例，保留无障碍表格与空态。
- [ ] **Step 4: 验证通过。** 在 `frontend/` 运行 `npm test`、`npm run build`、`npm run test:e2e` 验证 mock 场景；按 README 启动 PostgreSQL profile 的运行服务与平台后，设置 `DBT_POSTGRES_E2E=1` 并运行 `npm run test:e2e -- dbt-postgres-integration.spec.ts`，必须完成未拦截 API 的发布目录→维度值→月粒度查询。检查本子仓库及根仓库状态。

## 计划完成后的提交

依工作区规则，整份前端计划执行完且所有相关测试通过后，于 `metric-platform` 创建一个代码 commit；不按单个 Task 提交，不包含另一份后端计划的未完成改动或用户无关文件。实施前须遵循用户对提交的明确授权。
