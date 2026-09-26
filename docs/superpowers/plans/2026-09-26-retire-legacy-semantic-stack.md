# 退役平台自有语义定义与查询 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 平台只控制 dbt 发布、展示已发布目录，并将固定版本查询交给运行服务；移除平台自有的定义和 SQL 执行入口。

**Architecture:** 保留 `dbt/`、身份认证和共用 Git 唤醒入口。删除旧语义对象管理、旧查询编译执行及其前端入口，并同步收窄 OpenAPI。已应用的 Flyway 历史保持不变，旧数据保留以便迁移和审计。

**Tech Stack:** Java 21、Spring Boot、PostgreSQL/Flyway、React、TypeScript、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-26-dbt-full-build-publication-design.md`，以及用户本轮确认的平台职责边界。

## Global Constraints

- 只修改 `metric-platform/`；不引入 Agent 交互层。
- 不改写已应用的 Flyway 迁移，不删除历史数据。
- 公开路由变更同步 `api/openapi.yaml`。
- 在当前目录执行，不创建额外 worktree。
- 旧数据源管理与 Ossie 0.1.1 清理范围已得到用户确认，纳入本次删除。

## Review Focus

- 旧 `/api/v1/metrics`、`/dimensions`、`/datasets`、`/queries` 不再暴露；保留的 dbt 路由仍有契约测试。
- 旧前端导航和入口不可见，dbt 项目、目录、查询入口仍可用。
- Git Webhook 仍唤醒 DBT 发布，旧 Ossie 依赖不应意外断开保留模式。
- 认证和审计服务仍能启动，旧表数据不会被迁移删除。
- dbt 版本化查询仍固定 `releaseId`，不回退到平台自有 SQL 查询。

---

### Task 1: 收窄前端入口

**Files:** `frontend/src/routes.tsx`、`frontend/src/layout/PlatformLayout.tsx`、`frontend/src/layout/PlatformLayout.test.tsx`、旧功能组件及 `frontend/src/api/`。

**Interfaces:** 保留 `createMetricPlatformApi` 的 dbt 方法，删除旧定义与查询方法。

- [x] 先修改导航测试，要求 dbt 项目可见且旧入口不可见；运行测试看到失败。
- [x] 移除旧路由、菜单、旧组件及专属 API/types；运行前端测试与构建。

### Task 2: 退役后端旧语义链路

**Files:** `backend/src/main/java/com/metricplatform/{semantic,metadata,query}`、相关持久化类、`api/ApiExceptionHandler.java`、相关测试。

**Interfaces:** 保留 `dbt/`、身份服务和 Git DBT 发布接口；旧定义与 SQL 查询 Controller 消失。

- [x] 修改契约测试断言旧路由不存在、dbt 路由仍存在；运行测试看到失败。
- [x] 删除旧定义和查询实现与只服务于它们的持久化类型；调整异常处理和失效的旧功能测试。
- [x] 运行 Maven 编译及相关测试，修复剩余依赖。

### Task 3: 同步契约与说明

**Files:** `api/openapi.yaml`、`README.md`、`docs/` 中现行运行说明，以及 Task 1/2 相关测试。

**Interfaces:** OpenAPI 只描述实际保留的公开 API。

- [x] 从 OpenAPI 移除退役路由及不再引用的 schema，保持 dbt、认证和 Webhook 契约。
- [x] 更新 README 中平台职责与启动说明；保留历史迁移与历史设计文档。
- [x] 运行 `mvn -B verify`、`npm run build` 及前端测试；检查 Git diff 与状态。

### Task 4: 处理已确认的旧数据源和 Ossie 边界

**Files:** `source/`、`gitsync/`、`metric-git-sync/`、相关 UI/API/测试/文档。

**Interfaces:** DBT Git 快照读取和 Webhook 必须保留；运行服务持有 profile 绑定。

- [x] 运行现有 DBT Git 快照、Webhook 与发布编排测试，再移除旧数据源及 Ossie 实现。
- [x] 运行完整验证，确保 DBT 路径仍可编译、测试并与 OpenAPI 一致。
