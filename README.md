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
