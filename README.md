# Metric Platform

Metric Platform is an independent Spring Boot and React application for governed data-source metadata, semantic datasets, dimensions, metrics, relationships, SQL compilation, and query execution.

## Local development

Run from the Metric Platform repository root:

```powershell
docker compose up -d
.\mvnw.cmd -f .\backend\pom.xml spring-boot:run
npm.cmd --prefix .\frontend run dev
```

The metadata PostgreSQL service listens on port `5433`; the backend defaults to port `8090`. Copy `.env.example` to `.env` when local overrides are needed. StarRocks connections must use a read-only account.

Build independently with:

```powershell
.\mvnw.cmd -f .\backend\pom.xml -DskipTests package
npm.cmd --prefix .\frontend run build
docker compose config --quiet
```

The public contract is `api/openapi.yaml`. Callers create a source, explicitly select discovered fields, define semantic objects, validate a semantic request, and execute it through `/api/v1/queries`; callers never supply arbitrary SQL.

## Identity authentication for Data Agent

Metric Platform owns the legal identities used for governed metric access. Data Agent stores a reference to one configured username/password credential, exchanges it at `POST /api/v1/auth/token` with audience `data-agent`, and uses the returned bearer token for subsequent calls. Tokens expire after exactly ten minutes, are stored in Metric Platform only as SHA-256 hashes, and are invalidated when the password, account status, role, workspace membership, or dataset grants change.

For the first local administrator, explicitly enable the one-time bootstrap and provide the secret through environment variables:

```powershell
$env:METRIC_PLATFORM_BOOTSTRAP_ENABLED='true'
$env:METRIC_PLATFORM_BOOTSTRAP_TENANT_KEY='default'
$env:METRIC_PLATFORM_BOOTSTRAP_USERNAME='platform-admin'
$env:METRIC_PLATFORM_BOOTSTRAP_PASSWORD='<strong-local-secret>'
.\mvnw.cmd -f .\backend\pom.xml spring-boot:run
```

Disable bootstrap after the administrator exists. Use the `/api/v1/admin/platform-users` endpoints to create identities, replace passwords, change status, and replace dataset grants. `discoverAllowed` controls catalog/model/member visibility; `queryAllowed` is checked across every metric, dimension, filter, time range, sort, and relationship referenced by validation or execution. Authentication failures are intentionally generic, and passwords and bearer tokens must never be written to logs or audit details.
