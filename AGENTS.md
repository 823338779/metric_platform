# Metric Platform Instructions

This repository owns the governed metric and semantic layer. It must remain independently buildable and must not depend on Data Agent implementation classes.

## Project map

- `backend/`: Spring Boot API, domain services, persistence, security, Flyway migrations, and query compilation/execution.
- `frontend/`: React/Vite metric-management console.
- `api/openapi.yaml`: published integration contract.
- `docker-compose.yml`: standalone PostgreSQL development service.

## Change boundaries

- Data-source metadata, datasets, fields, dimensions, metrics, relationships, semantic SQL, governed queries, identity, and their management UI belong here.
- Keep tenant ownership and authorization checks at every persistence and HTTP boundary.
- Keep public routes, request/response schemas, authentication, and error behavior aligned with `api/openapi.yaml`.
- Data Agent integration must use the published API. Do not import or reach into the parent workspace or Data Agent source tree.
- Keep schema changes in versioned Flyway migrations; do not rewrite an applied migration.

## Coding and verification

- Add sufficient comments for new or non-obvious semantic rules, authorization constraints, SQL-generation invariants, and destructive operations.
- Do not add, delete, or modify test-related code unless the user explicitly requests it. Repository migration may update harness paths without changing assertions or expected behavior.
- Never commit real credentials, connection details, tokens, or customer data.
- Run the narrowest relevant backend or frontend check first, then broaden verification when a change crosses boundaries.

The checked-in Maven wrapper is incomplete because `.mvn/wrapper/maven-wrapper.properties` is absent. Use the installed `mvn` command for backend work; run frontend commands from `frontend/`.
