# Metric Platform Authentication-Only Design

## Goal

Metric Platform keeps username/password authentication and ten-hour opaque access tokens, but removes all authorization and tenancy concepts. Every authenticated account has identical access to every Metric Platform resource and operation. This change is limited to the `metric-platform` project; Data Agent is not modified.

## Chosen Approach

Remove the obsolete concepts completely instead of bypassing their checks or retaining compatibility placeholders. The public authentication contract, persistence model, Spring Security rules, query flow, frontend types, and documentation will no longer mention tenants, workspaces, roles, scopes, or dataset grants.

This is intentionally a breaking change for external consumers of the old identity response. In particular, the existing Data Agent Metric Platform identity integration will remain unchanged and will not be compatible with the simplified response until separately adapted.

## Authentication Model

- `platform_user` remains the account record.
- Usernames remain globally unique.
- Password hashing, account status, failed-login lockout, authentication versioning, token hashing, historical revocation state, and the ten-hour token lifetime remain.
- `POST /api/v1/auth/token` and `GET /api/v1/auth/me` remain. Frontend logout continues to clear the local session; no new server-side logout endpoint is introduced.
- The bootstrap configuration remains capable of creating the configured initial account. The existing `admin` account and its password are preserved by migration.
- The identity response contains only account identity fields needed by Metric Platform, such as identity ID, username/subject, display name, and authentication version.
- No registration or general account-management API is introduced.

## Removed Concepts

The implementation removes:

- platform tenants and tenant identifiers;
- platform workspaces and user-workspace memberships;
- platform roles and role codes;
- derived scopes and Spring Security authorities;
- the obsolete fixed token audience and client identifier;
- dataset discover/query grants;
- the legacy role-bearing API client model;
- administrator-only identity management endpoints;
- role-based SQL response differences;
- frontend identity fields and documentation for all removed concepts.

## Request Authorization

Spring Security continues to authenticate bearer tokens. Public endpoints remain limited to login and existing explicitly public operational endpoints. Every business endpoint uses a single `authenticated()` requirement. The authentication filter creates an authenticated principal without roles or authorities.

An unauthenticated or expired token continues to produce HTTP 401, so the frontend authentication guard continues redirecting to the login page. An authenticated account can use every management, catalog, semantic search, validation, query, and member-search endpoint.

## Query Flow

`MetricAuthorizationService` and `QueryPermission` are removed. Semantic search and resolve are unfiltered and operate across the entire global catalog. Dataset-model and dimension-member operations retain normal existence and semantic validation but perform no caller-specific access check.

Query validation and execution still resolve referenced datasets so malformed or missing semantic object references fail normally and audit events can record affected dataset IDs. That responsibility moves to a query-domain component with no authorization terminology. Query audit operation names distinguish validation from execution without representing permissions.

All authenticated accounts receive the same query response shape. The role-dependent SQL redaction branch is removed.

## Database Migration

A new Flyway migration is added; existing migrations are not rewritten.

The migration preserves `platform_user`, `platform_access_token`, all governed resource tables, and their current rows. It removes role and tenant columns and constraints from `platform_user`, removes the fixed audience column from `platform_access_token`, and then removes permission and tenancy tables in foreign-key-safe order:

1. `platform_identity_dataset_grant`
2. `platform_user_workspace`
3. `platform_workspace`
4. `platform_tenant`
5. legacy `api_client`

Because governed resource tables currently have no tenant column, no resource backfill is necessary. All existing data sources, datasets, fields, relationships, dimensions, and metrics remain in the single global resource space.

Existing opaque access-token rows should remain usable because they reference accounts directly and authentication still validates account status and authentication version. If a token cannot be reconstructed under the simplified model, it fails as HTTP 401 and the frontend follows the existing re-login path.

## Frontend

The login page, token storage, authentication guard, expired-session redirect, and original-route restoration remain. Authentication types remove tenant IDs, workspace IDs, roles, and scopes. No authorization or tenant UI is retained or added.

## Compatibility Boundary

Only files under `metric-platform` are changed. Data Agent source, tests, schemas, and frontend are untouched. Its current Metric Platform identity integration depends on fields removed by this design and may fail at runtime; adapting that integration requires a separate request.

## Verification

Metric Platform tests may be added, removed, or updated as explicitly authorized by the user. Verification will cover:

- unauthenticated business requests return 401;
- valid username/password login succeeds and tokens expire after ten hours;
- authenticated accounts can access management and semantic/query endpoints without roles, scopes, tenants, workspaces, or grants;
- semantic search returns the existing metrics and dimensions without dataset grants;
- expired or revoked tokens still trigger the frontend login flow;
- Flyway migrates the current database while retaining the `admin` account and governed resources;
- backend tests/build and frontend tests/build complete successfully.

## Out of Scope

- Any Data Agent change.
- Multi-tenant isolation.
- Role or permission compatibility fields.
- Registration, invitation, or a replacement account-management system.
- Git commit, branch, push, or pull request creation.
