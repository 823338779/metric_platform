# Metric Platform Instructions

This repository owns the governed metric and semantic layer. Keep it independently buildable and independent of Data Agent implementation classes.

## Project map

- `backend/`: Spring Boot API, domain services, persistence, authentication, Flyway migrations, and semantic query compilation and execution.
- `frontend/`: React/Vite metric-management console.
- `api/openapi.yaml`: published integration contract.
- `docker-compose.yml`: standalone PostgreSQL development service.

## Change boundaries

- Data-source metadata, datasets, fields, dimensions, metrics, relationships, semantic SQL, governed queries, identities, and their management UI belong here.
- The current platform uses global authenticated identities and is not tenant-, workspace-, role-, or dataset-grant-scoped. Do not reintroduce those semantics unless the requirement explicitly calls for them and the migrations, implementation, and OpenAPI contract change together.
- Treat authentication, access-token lifecycle, data-source credentials, audit attribution, semantic SQL generation and execution, and destructive mutations as security or correctness boundaries.
- For an intentional public API change, update `api/openapi.yaml` and keep routes, schemas, authentication, errors, implementation, tests, and consumers aligned with it.
- Data Agent integration must use the published HTTP API. Do not import or reach into the parent workspace or Data Agent source tree.
- Preserve the existing semantic query compiler and parameter-binding paths; do not bypass them with ad hoc SQL string construction.
- Keep schema changes in versioned Flyway migrations and never rewrite an applied migration.

## Coding and verification

- Add comments for non-obvious semantic rules, authentication constraints, SQL-generation invariants, and destructive operations; do not comment self-evident code.
- Do not delete, weaken, or rewrite tests merely to make a change pass. Bug fixes and new behavior may add the smallest relevant regression tests; broad test-suite changes require explicit user approval.
- Never commit real credentials, connection details, tokens, or customer data.
- Use the supported commands in `README.md`. Run the narrowest affected backend or frontend test first, then broaden verification when a change crosses boundaries.
- The checked-in Maven wrapper is incomplete because `.mvn/wrapper/maven-wrapper.properties` is absent. Use the installed `mvn` command for backend work; run frontend commands from `frontend/`.
