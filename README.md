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

## Account authentication

Metric Platform has one global resource space. Usernames are unique across the platform, and a caller exchanges only a username and password at `POST /api/v1/auth/token`. Tokens expire after exactly ten hours, are stored only as SHA-256 hashes, and are rejected when expired, revoked, or no longer consistent with the account authentication version. Every authenticated account can access every Metric Platform resource and operation; the platform has no tenants, workspaces, roles, scopes, or dataset grants.

To create the initial local account, explicitly enable the one-time bootstrap and provide the secret through environment variables:

```powershell
$env:METRIC_PLATFORM_BOOTSTRAP_ENABLED='true'
$env:METRIC_PLATFORM_BOOTSTRAP_USERNAME='admin'
$env:METRIC_PLATFORM_BOOTSTRAP_PASSWORD='<strong-local-secret>'
.\mvnw.cmd -f .\backend\pom.xml spring-boot:run
```

Disable bootstrap after the account exists. There is no registration or account-management API. Authentication failures are intentionally generic, and passwords and bearer tokens must never be written to logs or audit details.

This authentication response intentionally contains only account identity fields. Data Agent is a separate project and is not changed here; integrations that still expect Metric Platform tenant, workspace, role, or scope fields require a separate compatibility update.
