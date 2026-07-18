# Metric Platform Agent Semantic API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. This workspace forbids subagent dispatch and unrequested branches, commits, or test-file changes.

**Goal:** Deliver the Metric Platform side of the Agent semantic-query vertical slice: governed metadata, ranked discovery, semantic resolution, dimension-member lookup, expressive validated queries, evidence-rich results, and query-client authorization.

**Architecture:** Metric Platform remains an independent Spring Boot service and publishes all capability through `/api/v1` and `api/openapi.yaml`. New agent-facing controllers delegate to focused catalog/member/query services; persisted semantic IDs and relationship planning remain authoritative, and callers never submit SQL or physical identifiers.

**Tech Stack:** Java 21, Spring Boot 3.5.16, MyBatis-Plus 3.5.16, Flyway, PostgreSQL/H2 metadata stores, read-only JDBC data sources, OpenAPI YAML, React/TypeScript client types.

## Global Constraints

- Do not import Data Agent or Spring AI implementation classes.
- Do not add, delete, or modify test-related code.
- Do not create branches, commits, or pushes unless explicitly requested.
- Preserve the current uncommitted external-SQL logging changes, especially in `QueryService`.
- Do not expose JDBC credentials, physical fields, joins, or compiled SQL through agent APIs.
- Stage-three vector search, derived metrics, tenant-token issuance, and durable planToken storage are outside this implementation slice.
- Use Java 21 through `JAVA_HOME=E:\env\jdks\ms-21.0.11` for Maven verification.

---

### Task 1: Persist agent-facing semantic metadata

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__agent_semantic_api.sql`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/MetricEntity.java`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/DimensionEntity.java`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/DatasetEntity.java`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/DatasetFieldEntity.java`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/DatasetRelationshipEntity.java`

**Interfaces:**
- Stores aliases as a JSON array property on dataset, metric, and dimension rows.
- Produces optional metadata columns with safe defaults: `status=ACTIVE`, `certification_status=UNCERTIFIED`, `metric_type=BASE`, and `sensitivity_level=INTERNAL`.

- [ ] Add V7 columns for descriptions, ownership/domain/status/certification, metric unit/format/behavior, dimension semantic/value/sensitivity metadata, dataset business grain/freshness/quality, field description/sensitivity, and relationship description/display path/role/fanout safety.
- [ ] Add an `aliases` text column to dataset, metric, and dimension with an empty JSON-array default.
- [ ] Map every new column with explicit getters and setters on the existing entities.
- [ ] Map aliases directly on each semantic entity; do not create a polymorphic alias table.
- [ ] Run `mvnw -DskipTests compile` with Java 21; expect `BUILD SUCCESS`.

### Task 2: Extend semantic definition CRUD without breaking existing callers

**Files:**
- Modify: `backend/src/main/java/com/metricplatform/semantic/SemanticDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/semantic/SemanticDefinitionService.java`

**Interfaces:**
- Produces `SemanticMetadata` with description, aliases, domain, owner, status, certification status, and type-specific fields.
- Existing request constructor arities remain available through compatibility constructors.

- [ ] Extend metric create/update/response DTOs with description, aliases, unit, format, status, domain, owner, certification, aggregation behavior, additivity, metric type, and default time dimension.
- [ ] Extend dimension create/update/response DTOs with description, aliases, status, domain, owner, certification, business definition, semantic type, value type, default granularity, sensitivity level.
- [ ] Preserve old constructor signatures used by current tests and frontend callers by delegating to safe defaults.
- [ ] Serialize normalized alias lists into the owning entity on create/update.
- [ ] Include description and aliases in list filtering and response mapping.
- [ ] Run Java 21 compile.

### Task 3: Extend dataset, field, and relationship metadata

**Files:**
- Modify: `backend/src/main/java/com/metricplatform/metadata/dataset/DatasetDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/metadata/dataset/DatasetService.java`
- Modify: `backend/src/main/java/com/metricplatform/metadata/relationship/RelationshipDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/metadata/relationship/DatasetRelationshipService.java`

**Interfaces:**
- Dataset responses expose business grain, domain, owner, status, certification, freshness policy, quality status, and default time dimension.
- Relationship responses expose agent-safe description/display path/semantic role/fanout status.

- [ ] Add backward-compatible optional metadata fields and old constructors to dataset DTOs.
- [ ] Persist and map dataset business metadata on create/update.
- [ ] Add field description/sensitivity to field update/response while preserving the old constructor.
- [ ] Add backward-compatible relationship metadata fields and persist them on create/update.
- [ ] Derive display path from dataset names when omitted and keep physical field pairs out of agent resolve responses.
- [ ] Run Java 21 compile.

### Task 4: Add capabilities, ranked search, and semantic resolve

**Files:**
- Create: `backend/src/main/java/com/metricplatform/query/SemanticCatalogService.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryController.java`

**Interfaces:**
- `GET /api/v1/capabilities`
- `POST /api/v1/semantic-objects:search`
- `POST /api/v1/semantic-objects:resolve`

- [ ] Define capabilities DTO with API version, catalog version, supported query features, limits, time presets, and planToken support false.
- [ ] Extend search request with domains, statuses, certifiedOnly, and bounded topK while preserving the original constructor.
- [ ] Return candidates with description, aliases, score, match reasons, status, certification, domain, version, updatedAt, and type-specific summary.
- [ ] Use MyBatis query predicates to bound database candidates; merge alias matches and rank exact name, alias, display name, prefix, then description.
- [ ] Define batch resolve requests and agent-safe context responses containing dataset grain, object definitions, allowed granularities, units, freshness/quality, and candidate relationship paths.
- [ ] Move search/model response assembly from `QueryController` into `SemanticCatalogService`.
- [ ] Run Java 21 compile.

### Task 5: Add governed dimension-member lookup

**Files:**
- Create: `backend/src/main/java/com/metricplatform/query/DimensionMemberService.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryController.java`

**Interfaces:**
- `POST /api/v1/dimensions/{dimensionId}/members:search`
- Consumes persisted dimension, field, dataset, and active data-source metadata.

- [ ] Define nonblank query, AUTO/EXACT/PREFIX/CONTAINS modes, bounded topK, and member result DTOs.
- [ ] Reject restricted dimensions before opening a source connection.
- [ ] Compile one parameterized `SELECT DISTINCT` against persisted identifiers using `IdentifierQuoter`, a 5-second timeout, and server-side row limit.
- [ ] Return canonical/display values only; never return surrounding row data.
- [ ] Preserve existing external SQL logging conventions for the generated lookup query.
- [ ] Run Java 21 compile.

### Task 6: Extend QuerySpec and planning

**Files:**
- Modify: `backend/src/main/java/com/metricplatform/query/QueryDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/query/planner/QueryPlan.java`
- Modify: `backend/src/main/java/com/metricplatform/query/planner/QueryPlanner.java`

**Interfaces:**
- Query request adds `filters`, `timeRange`, `sort`, and `limit`; the original three-argument constructor remains.
- Query plan produces selected dimensions, filter projections, normalized time bounds, sort projections, and limit.

- [ ] Add typed dimension filter operators `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `BETWEEN`, `IN`, `CONTAINS`, `IS_NULL`, `IS_NOT_NULL`.
- [ ] Add absolute and preset time ranges with required timezone; support TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, MONTH_TO_DATE, QUARTER_TO_DATE, YEAR_TO_DATE, LAST_MONTH, LAST_QUARTER, LAST_YEAR.
- [ ] Add metric/dimension sort targets and enforce that targets are selected.
- [ ] Normalize limit to the server maximum of 1000.
- [ ] Load filter-only dimensions and include their safe relationship paths in every fact aggregate.
- [ ] Validate dimension types, filter arity, time dimension type, timezone, and relationship uniqueness before compilation.
- [ ] Run Java 21 compile.

### Task 7: Compile filters, time ranges, ordering, and limits

**Files:**
- Modify: `backend/src/main/java/com/metricplatform/query/compiler/SemanticSqlCompiler.java`
- Modify: `backend/src/main/java/com/metricplatform/query/compiler/CompiledQuery.java` only if result metadata is required.

**Interfaces:**
- Consumes the extended `QueryPlan` from Task 6.
- Produces parameterized SQL without client expressions or identifiers.

- [ ] Combine dataset fixed filters and runtime dimension/time predicates with `AND` inside each fact aggregate.
- [ ] Resolve runtime filter columns through persisted dimension fields and aggregate dataset aliases.
- [ ] Bind every runtime literal with an inferred JDBC type; never interpolate values.
- [ ] Apply final result ordering by stable generated aliases and append the validated limit.
- [ ] Keep dimensions-first, metrics-second output order and existing multi-fact full-outer-join behavior.
- [ ] Run existing query compiler and planner test classes without modifying them.

### Task 8: Return structured validation and evidence-rich query results

**Files:**
- Create: `backend/src/main/java/com/metricplatform/query/QueryValidationException.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryService.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryController.java`
- Modify: `backend/src/main/java/com/metricplatform/api/ApiExceptionHandler.java`

**Interfaces:**
- Validation response always carries `valid`, issues, warnings, normalized request, plan summary, catalog version, and planToken null.
- Query response carries queryId, semantic columns, row count, truncation, applied filters/time range, freshness warnings, catalog version, and evidence.

- [ ] Map planner and catalog failures to stable issue codes instead of parsing them in Data Agent.
- [ ] Return candidate relationship paths for ambiguous path issues.
- [ ] Add semantic object IDs, display names, logical types, units, formats, and granularity to result columns.
- [ ] Preserve the current external SQL logger call when restructuring `QueryService`.
- [ ] Detect truncation by requesting one row beyond the response limit where supported, then return at most the validated limit.
- [ ] Generate queryId and definition-version evidence without exposing SQL.
- [ ] Run Java 21 compile and focused query tests.

### Task 9: Enforce query-client authorization

**Files:**
- Modify: `backend/src/main/java/com/metricplatform/security/PlatformSecurityConfig.java`

**Interfaces:**
- `METRIC_QUERY_CLIENT` may call capabilities, semantic model/search/resolve, member search, validate, and execute.
- Management reads/writes require PLATFORM_ADMIN or SEMANTIC_EDITOR; local admin-token remains compatible.

- [ ] Add ordered request matchers for the agent-safe read/query endpoints.
- [ ] Require PLATFORM_ADMIN or SEMANTIC_EDITOR for semantic and data-source management endpoints.
- [ ] Keep query-token unable to access credentials, source discovery, previews, or management CRUD.
- [ ] Preserve local development tokens; durable tenant-scoped token issuance remains a later release.
- [ ] Run security-related existing tests without modifying them.

### Task 10: Publish the OpenAPI contract and frontend type compatibility

**Files:**
- Modify: `api/openapi.yaml`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/metricPlatformApi.ts` only where the expanded response breaks compilation.

**Interfaces:**
- OpenAPI defines every new request, response, enum, issue, result-evidence, and endpoint shape.
- Existing frontend requests remain source compatible because new request properties are optional.

- [ ] Add capabilities, resolve, and member-search paths.
- [ ] Expand semantic object, QuerySpec, validation, and query-result schemas.
- [ ] Document role restrictions and RFC 9457 error responses.
- [ ] Extend TypeScript types with optional request fields and complete response types.
- [ ] Parse `api/openapi.yaml` with the workspace Python YAML library or equivalent.
- [ ] Run frontend production build.

### Task 11: Final verification and scope audit

**Files:**
- Inspect all modified files; do not alter tests.

- [ ] Run `git diff --check` and confirm no whitespace errors in changed files.
- [ ] Run Java 21 `mvnw -DskipTests package` and confirm `BUILD SUCCESS`.
- [ ] Run focused existing planner/compiler/controller tests that are not blocked by the known H2 V3 migration failure.
- [ ] Run the full existing backend test suite and record the known baseline OpenAPI path-count and H2 V3 migration failures separately from new failures.
- [ ] Run `npm.cmd --prefix frontend run build` and confirm TypeScript/Vite success.
- [ ] Parse OpenAPI and inspect that no response exposes password, jdbcUrl, physicalName, fieldPairs, or SQL in agent-safe schemas.
- [ ] Compare implementation against `docs/superpowers/specs/2026-07-18-agent-semantic-api-design.md`; list stage-three exclusions explicitly.
