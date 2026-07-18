# Metric Platform Agent Guide

This repository contains only Metric Platform. Work here for data-source metadata, datasets, fields, dimensions, metrics, relationships, semantic SQL compilation, governed queries, the management console, and the published OpenAPI contract.

## Project map

- Backend: `backend/`
- Frontend: `frontend/`
- OpenAPI contract: `api/openapi.yaml`
- Black-box harness: `testcases/`
- Local PostgreSQL: `docker-compose.yml`

Read `README.md` and the relevant `backend/pom.xml` or `frontend/package.json` before editing. Keep the repository independently buildable; do not import Data Agent implementation classes or reach into a parent workspace. Data Agent integration happens through the published API.

Do not add, delete, or modify test-related code unless the user explicitly requests it. Repository-migration work may update filesystem paths in existing harness scripts, but must not change assertions or expected behavior.
