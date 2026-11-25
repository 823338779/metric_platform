# MCP 迁入指标平台实施计划

执行 Superpowers 的 subagent-driven-development 流程，由主任务统一验证和提交。

**目标**　在平台中独立安装 stdio MCP，沿用七个工具和复合分组行为。

**架构与技术**　Python MCP SDK 只连接平台 HTTP API。Spring 后端通过现有引擎端口访问 dbt-service。设计见 [MCP 迁入 Metric Platform](../specs/2026-11-25-mcp-migration-design.md)。

## 全局约束与评审重点

固定 buildId 和登录身份。重点核对跨租户搜索、精确查找同名指标、绑定中混入未授权维度、部署切换及构建提交超时后复用幂等键。

## 实施与验证

- [x] **目录搜索**　在 BuildGateway、dbt HTTP 适配和 PublishedCatalogController 增加固定构建分页搜索。同步 OpenAPI，用后端测试覆盖租户归属、精确 ID、游标和上游错误。
- [x] **复合分组**　扩展 BuildContracts、GrantedQuery、请求校验和 OpenAPI，支持共享 optionId 和覆盖全部所选指标的 bindings。验证同一选项在不同指标中的归属、粒度、输出名排序和部分授权被拒。
- [x] **stdio 进程**　在 mcp/ 独立打包 metric-platform-mcp，改用平台令牌、路由、构建输入和 Problem 错误。通过独立 Python 客户端列出七个工具，用带平台鉴权的 HTTP 夹具覆盖查询及构建操作。
- [x] **旧服务清理与交付**　迁移原 MCP 测试和调用示例，移除旧入口、代码、专属依赖、过时说明，更新锁文件。分别验证平台、Python 包、dbt-service 及根仓库差异，仅在两个归属仓库提交本次改动。
