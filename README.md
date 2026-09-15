# Metric Platform

Metric Platform is the governed metric and semantic layer used by Data Agent. It manages data sources, datasets and fields, dimensions, metrics, dataset relationships, semantic models, validation, query execution, identity, and the management console.

## Project layout

| Path | Responsibility |
| --- | --- |
| `backend/` | Spring Boot API, domain services, persistence, security, Flyway migrations, and semantic/query execution. |
| `metric-git-sync/` | 独立的 Git 快照读取与 Ossie Profile 解析模块。 |
| `frontend/` | React and Vite management console. |
| `api/openapi.yaml` | Published HTTP API contract. |
| `docker-compose.yml` | Standalone PostgreSQL development service. |

## Prerequisites

- JDK 21
- Node.js and npm
- PostgreSQL
- Redis on `localhost:6379` for identity token state

## Run in the Agent Workspace

When this repository is checked out under `agent-root`, use the workspace Compose project. It supplies PostgreSQL, creates the `metric_platform` database with the application's default credentials, and starts Redis:

```powershell
docker compose -f .\infra\compose\compose.yml up -d
```

From the workspace root, start `MetricPlatformApplication` from `.run/`, or run the backend from this repository:

```powershell
mvn -f .\metric-platform\pom.xml -pl metric-git-sync -am install -DskipTests
mvn -f .\metric-platform\backend\pom.xml spring-boot:run
```

The API listens on port `8090`.

## Run as a standalone repository

The repository-local Compose file exposes PostgreSQL on port `5433` with standalone credentials. Override the datasource settings for that service:

```powershell
docker compose up -d postgres
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5433/metric_platform'
$env:SPRING_DATASOURCE_USERNAME = 'metric_platform'
$env:SPRING_DATASOURCE_PASSWORD = 'metric_platform'
mvn -pl metric-git-sync -am install -DskipTests
mvn -f .\backend\pom.xml spring-boot:run
```

Redis must be supplied separately or configured through the standard `SPRING_DATA_REDIS_*` properties.

Start the management console in another terminal:

```powershell
Set-Location frontend
npm ci
npm run dev
```

The Vite development server proxies `/api` to `http://localhost:8090`.

## Build and verify

```powershell
mvn -B verify

Set-Location frontend
npm ci
npm run build
```

## API boundary

[The OpenAPI contract](api/openapi.yaml) is the integration boundary for Data Agent and other clients. Changes to public routes, payloads, authentication, or error behavior must update the contract together with the implementation.

## Git 定义订阅

新增的 `metric-git-sync` 模块消费 Ossie 0.1.1 的 `ossie-metrics` Profile v1，
以单个配置仓库的 `main:semantic/model.yaml` 为定义来源。默认关闭；启用后使用
Webhook、启动扫描和轮询同步，整份快照原子发布，重复提交不会重复创建对象。
Git 托管对象通过仓库维护，管理写接口返回 409；人工对象继续沿用既有流程。

部署参数、Git hook、协议边界及恢复方法见 [Git 订阅与消费说明](docs/git-sync.md)。
Git 服务端示例 hook 位于 [ops/git/post-receive](ops/git/post-receive)。
