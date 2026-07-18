# Metric Platform Authentication-Only Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Metric Platform tenants and every authorization concept while retaining global username/password login and ten-hour opaque access tokens.

**Architecture:** Keep a simplified account principal and require only `authenticated()` for Metric Platform business APIs. Remove role/scope/grant filtering, route semantic queries through an authorization-free dataset-reference resolver, and migrate the existing database in place without changing governed resources or the `admin` account.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Security, MyBatis-Plus 3.5, Flyway/PostgreSQL, React 19, TypeScript 5.8, Vitest, Maven, Vite.

## Global Constraints

- Modify only `metric-platform`; do not change Data Agent files.
- Preserve username/password authentication, account lockout, token hashing, historical revocation state, and an exact ten-hour token TTL.
- Preserve the current `admin` account and all governed resource rows.
- Remove tenants, workspaces, roles, scopes, grants, fixed token audience/client identity, legacy API clients, and administrator identity-management APIs completely.
- Every valid logged-in account has identical access to every Metric Platform business API.
- Unauthenticated, expired, or revoked tokens still receive HTTP 401 and trigger the existing frontend login redirect.
- The user authorized adding, deleting, and modifying Metric Platform tests for this change.
- Do not create commits, branches, pushes, or pull requests.
- Preserve the pre-existing dirty changes in `README.md`, `frontend/src/auth/AuthenticationGuard.tsx`, and `frontend/src/features/auth/LoginPage.tsx` except for scoped edits required here.

---

## File Structure

**Create**

- `backend/src/main/resources/db/migration/V10__remove_authorization_and_tenancy.sql`: forward-only schema cleanup.
- `backend/src/test/java/com/metricplatform/persistence/AuthenticationOnlyMigrationTest.java`: migrated-schema and data-preservation contract.
- `backend/src/test/java/com/metricplatform/identity/PlatformAuthenticationServiceTest.java`: simplified account/token behavior.
- `backend/src/main/java/com/metricplatform/query/QueryDatasetResolver.java`: semantic reference validation and audit dataset collection without caller authorization.
- `backend/src/test/java/com/metricplatform/query/QueryDatasetResolverTest.java`: resolver behavior.
- `backend/src/main/java/com/metricplatform/query/QueryOperation.java`: `VALIDATE`/`EXECUTE` audit operation names.
- `backend/src/main/java/com/metricplatform/query/MetricQueryAuditWriter.java`: query audit writer moved out of the authorization package.
- `frontend/src/api/authApi.test.ts`: simplified authentication response contract.

**Modify**

- `backend/src/main/java/com/metricplatform/identity/IdentityDtos.java`: basic account identity only.
- `backend/src/main/java/com/metricplatform/identity/PlatformAuthenticationService.java`: global-user authentication with no tenant/workspace/role/scope/audience lookup.
- `backend/src/main/java/com/metricplatform/identity/PlatformIdentityBootstrap.java`: bootstrap the configured global account.
- `backend/src/main/java/com/metricplatform/identity/PlatformIdentityProperties.java`: remove bootstrap tenant key.
- `backend/src/main/java/com/metricplatform/identity/PlatformIdentityAuditWriter.java`: remove client identity metadata and permission details.
- `backend/src/main/java/com/metricplatform/security/PlatformPrincipal.java`: account identity and credential metadata only.
- `backend/src/main/java/com/metricplatform/security/PlatformTokenAuthenticationFilter.java`: authenticate with an empty authority list.
- `backend/src/main/java/com/metricplatform/security/PlatformSecurityConfig.java`: one authenticated rule for all `/api/v1/**` business endpoints.
- `backend/src/main/java/com/metricplatform/persistence/entity/PlatformUserEntity.java`: remove tenant and role fields.
- `backend/src/main/java/com/metricplatform/persistence/entity/PlatformAccessTokenEntity.java`: remove audience.
- `backend/src/main/java/com/metricplatform/persistence/entity/AuditEventEntity.java`: remove obsolete client ID.
- `backend/src/main/java/com/metricplatform/query/QueryController.java`: remove caller-specific filters/checks and use query-domain resolver/audit types.
- `backend/src/test/java/com/metricplatform/query/QueryControllerTest.java`: assert equal access for any authenticated account.
- `backend/src/test/java/com/metricplatform/MetricPlatformApplicationTest.java`: remove the legacy API-client mapping contract.
- `backend/src/test/java/com/metricplatform/persistence/AuditEntityContractTest.java`: remove `ApiClientEntity` from the audited entity set.
- `backend/src/test/java/com/metricplatform/persistence/AuditColumnsMigrationTest.java`: remove `api_client` from the active business-table set.
- `backend/src/main/resources/application.yml`: remove bootstrap tenant configuration.
- `frontend/src/api/auth.ts`: remove tenant/workspace/role/scope fields.
- `README.md`: document authentication-only behavior and breaking Data Agent compatibility boundary.

**Delete**

- `backend/src/main/java/com/metricplatform/authorization/MetricAuthorizationService.java`
- `backend/src/main/java/com/metricplatform/authorization/MetricQueryAuditWriter.java`
- `backend/src/main/java/com/metricplatform/authorization/QueryPermission.java`
- `backend/src/main/java/com/metricplatform/security/PlatformScopePolicy.java`
- `backend/src/main/java/com/metricplatform/identity/PlatformIdentityAdminController.java`
- `backend/src/main/java/com/metricplatform/identity/PlatformIdentityAdminService.java`
- `backend/src/main/java/com/metricplatform/persistence/entity/ApiClientEntity.java`
- `backend/src/main/java/com/metricplatform/persistence/entity/PlatformTenantEntity.java`
- `backend/src/main/java/com/metricplatform/persistence/entity/PlatformWorkspaceEntity.java`
- `backend/src/main/java/com/metricplatform/persistence/entity/PlatformUserWorkspaceEntity.java`
- `backend/src/main/java/com/metricplatform/persistence/entity/PlatformIdentityDatasetGrantEntity.java`
- `backend/src/main/java/com/metricplatform/persistence/mapper/ApiClientMapper.java`
- `backend/src/main/java/com/metricplatform/persistence/mapper/PlatformTenantMapper.java`
- `backend/src/main/java/com/metricplatform/persistence/mapper/PlatformWorkspaceMapper.java`
- `backend/src/main/java/com/metricplatform/persistence/mapper/PlatformUserWorkspaceMapper.java`
- `backend/src/main/java/com/metricplatform/persistence/mapper/PlatformIdentityDatasetGrantMapper.java`
- `backend/src/main/java/com/metricplatform/persistence/service/ApiClientPersistenceService.java`
- `backend/src/main/java/com/metricplatform/persistence/service/PlatformTenantPersistenceService.java`
- `backend/src/main/java/com/metricplatform/persistence/service/PlatformWorkspacePersistenceService.java`
- `backend/src/main/java/com/metricplatform/persistence/service/PlatformUserWorkspacePersistenceService.java`
- `backend/src/main/java/com/metricplatform/persistence/service/PlatformIdentityDatasetGrantPersistenceService.java`
- `backend/src/main/resources/mapper/PlatformIdentityDatasetGrantMapper.xml`

---

### Task 1: Prove and Apply the Database Migration

**Files:**

- Create: `backend/src/test/java/com/metricplatform/persistence/AuthenticationOnlyMigrationTest.java`
- Create: `backend/src/main/resources/db/migration/V10__remove_authorization_and_tenancy.sql`
- Modify: `backend/src/test/java/com/metricplatform/MetricPlatformApplicationTest.java`

**Interfaces:**

- Produces a `platform_user` schema with global account fields and no `tenant_id`/`role_code`.
- Produces `platform_access_token` without `audience`, while retaining `revoked_at`.
- Removes all tenancy/authorization/legacy-client tables.

- [ ] **Step 1: Add the failing migration contract**

Create a Spring Boot/JdbcTemplate test that asserts the removed tables and columns are absent and retained tables are present:

```java
@SpringBootTest
@ActiveProfiles("test")
class AuthenticationOnlyMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void removesAuthorizationAndTenancySchema() {
        assertThat(tableCount("platform_user")).isEqualTo(1);
        assertThat(tableCount("platform_access_token")).isEqualTo(1);
        assertThat(columnCount("platform_user", "tenant_id")).isZero();
        assertThat(columnCount("platform_user", "role_code")).isZero();
        assertThat(columnCount("platform_access_token", "audience")).isZero();
        assertThat(tableCount("platform_tenant")).isZero();
        assertThat(tableCount("platform_workspace")).isZero();
        assertThat(tableCount("platform_user_workspace")).isZero();
        assertThat(tableCount("platform_identity_dataset_grant")).isZero();
        assertThat(tableCount("api_client")).isZero();
    }
}
```

- [ ] **Step 2: Run the migration contract and verify RED**

Run: `mvn -f backend/pom.xml -Dtest=AuthenticationOnlyMigrationTest test`

Expected: FAIL because V10 does not exist and old tables/columns remain.

- [ ] **Step 3: Add the forward-only V10 migration**

Use foreign-key-safe order and idempotent constraint removal:

```sql
DROP TABLE IF EXISTS platform_identity_dataset_grant;
DROP TABLE IF EXISTS platform_user_workspace;
DROP TABLE IF EXISTS platform_workspace;

ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS fk_platform_user_tenant;
ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS uq_platform_user_id_tenant;
ALTER TABLE platform_user DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE platform_user DROP COLUMN IF EXISTS role_code;

ALTER TABLE platform_access_token DROP COLUMN IF EXISTS audience;
ALTER TABLE audit_event DROP COLUMN IF EXISTS client_id;

DROP TABLE IF EXISTS platform_tenant;
DROP TABLE IF EXISTS api_client;
```

- [ ] **Step 4: Remove the API-client entity expectation**

Delete `"ApiClientEntity", "api_client"` from `MetricPlatformApplicationTest.mapsEveryMetadataTableToAnEntityAndBaseMapper`.

- [ ] **Step 5: Verify GREEN**

Run: `mvn -f backend/pom.xml -Dtest=AuthenticationOnlyMigrationTest,MetricPlatformApplicationTest test`

Expected: PASS with zero failures and zero errors.

---

### Task 2: Simplify the Authentication Domain

**Files:**

- Create: `backend/src/test/java/com/metricplatform/identity/PlatformAuthenticationServiceTest.java`
- Modify: `backend/src/main/java/com/metricplatform/identity/IdentityDtos.java`
- Modify: `backend/src/main/java/com/metricplatform/identity/PlatformAuthenticationService.java`
- Modify: `backend/src/main/java/com/metricplatform/identity/PlatformIdentityBootstrap.java`
- Modify: `backend/src/main/java/com/metricplatform/identity/PlatformIdentityProperties.java`
- Modify: `backend/src/main/java/com/metricplatform/security/PlatformPrincipal.java`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/PlatformUserEntity.java`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/PlatformAccessTokenEntity.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**

- `IdentitySummary(UUID identityId, String subject, String displayName, long authVersion)`
- `PlatformPrincipal(UUID identityId, String subject, String displayName, UUID credentialId, long authVersion)`
- `login(AuthTokenRequest, String)` still returns `AuthTokenResponse` with `expiresIn == 36000`.

- [ ] **Step 1: Add failing authentication tests**

Use Mockito-backed persistence services and a fixed clock. Cover successful login, no tenant lookup, simplified response, exact TTL, token hashing, expired-token rejection, revoked-token rejection, inactive-account rejection, and auth-version mismatch. The central assertion is:

```java
AuthTokenResponse response = service.login(new AuthTokenRequest("admin", "admin"), "127.0.0.1");

assertThat(response.expiresIn()).isEqualTo(36_000);
assertThat(response.identity())
        .isEqualTo(new IdentitySummary(userId, "admin", "Platform Administrator", 2));
verify(tokens).save(argThat(token -> token.getIdentityId().equals(userId)
        && token.getAuthVersion() == 2
        && token.getExpiresAt().equals(now.plus(Duration.ofHours(10)))));
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -f backend/pom.xml -Dtest=PlatformAuthenticationServiceTest test`

Expected: compilation/test failure because the simplified DTO/principal constructors do not exist and authentication still requires tenant/workspace services.

- [ ] **Step 3: Simplify DTOs, principal, and entities**

Reduce the records and entities to:

```java
public record IdentitySummary(
        UUID identityId, String subject, String displayName, long authVersion) {}

public record PlatformPrincipal(
        UUID identityId,
        String subject,
        String displayName,
        UUID credentialId,
        long authVersion) {}
```

Remove tenant/role fields from `PlatformUserEntity` and audience from `PlatformAccessTokenEntity`. Remove user-creation, status, password-replacement, and dataset-grant DTOs that only served the deleted admin API.

- [ ] **Step 4: Simplify authentication and bootstrap**

`PlatformAuthenticationService` must query `platform_user` by normalized global username, validate only account state/lock/password, store a hashed token with identity/auth-version/timestamps, and reconstruct the simplified principal without tenant/workspace/role/scope/audience dependencies.

`PlatformIdentityBootstrap` must skip creation when the configured normalized username already exists; otherwise create the global account with password/status/auth fields. Remove `tenantKey` from bootstrap properties and `application.yml`.

- [ ] **Step 5: Verify GREEN**

Run: `mvn -f backend/pom.xml -Dtest=PlatformAuthenticationServiceTest test`

Expected: PASS with zero failures and zero errors.

---

### Task 3: Replace Permission Rules with Authentication-Only Security

**Files:**

- Modify: `backend/src/test/java/com/metricplatform/query/QueryControllerTest.java`
- Modify: `backend/src/main/java/com/metricplatform/security/PlatformSecurityConfig.java`
- Modify: `backend/src/main/java/com/metricplatform/security/PlatformTokenAuthenticationFilter.java`
- Delete: `backend/src/main/java/com/metricplatform/security/PlatformScopePolicy.java`

**Interfaces:**

- `POST /api/v1/auth/token` remains public.
- All other `/api/v1/**` endpoints require an authenticated token and no authority.

- [ ] **Step 1: Change the controller security test to the desired behavior**

Keep the anonymous 401 assertion. Replace the forbidden-role test with an authenticated-access test:

```java
@Test
void authenticatedAccountCanUseManagementEndpoints() throws Exception {
    mvc.perform(post("/api/v1/admin/data-sources")
                    .header("Authorization", "Bearer query-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isBadRequest());
}
```

Construct the simplified `PlatformPrincipal` without roles or scopes.

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -f backend/pom.xml -Dtest=QueryControllerTest test`

Expected: FAIL because the authenticated account still receives 403.

- [ ] **Step 3: Implement authentication-only rules**

Replace endpoint-specific roles/scopes with:

```java
.authorizeHttpRequests(a -> a
        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
        .requestMatchers("/api/v1/**").authenticated()
        .anyRequest().permitAll())
```

In the token filter, create `UsernamePasswordAuthenticationToken(principal, null, List.of())`. Remove all authority construction and `PlatformScopePolicy`.

- [ ] **Step 4: Verify GREEN**

Run: `mvn -f backend/pom.xml -Dtest=QueryControllerTest test`

Expected: PASS, including anonymous 401 and authenticated management access.

---

### Task 4: Remove Dataset Authorization from the Query Path

**Files:**

- Create: `backend/src/test/java/com/metricplatform/query/QueryDatasetResolverTest.java`
- Create: `backend/src/main/java/com/metricplatform/query/QueryDatasetResolver.java`
- Create: `backend/src/main/java/com/metricplatform/query/QueryOperation.java`
- Create: `backend/src/main/java/com/metricplatform/query/MetricQueryAuditWriter.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryController.java`
- Delete: `backend/src/main/java/com/metricplatform/authorization/MetricAuthorizationService.java`
- Delete: `backend/src/main/java/com/metricplatform/authorization/MetricQueryAuditWriter.java`
- Delete: `backend/src/main/java/com/metricplatform/authorization/QueryPermission.java`

**Interfaces:**

- `QueryDatasetResolver.requireResolve(SemanticResolveRequest request)` validates referenced object and relationship existence.
- `QueryDatasetResolver.datasetIds(QueryRequest request)` returns the referenced dataset IDs or throws the existing generic request-validation failure for missing references.
- `QueryOperation` has `VALIDATE` and `EXECUTE` solely for audit action names.

- [ ] **Step 1: Add failing resolver tests**

Create Mockito-backed tests proving that metric, dimension, filter, time-range, sort, and relationship references contribute their datasets without consulting a principal or grant service. Include missing-reference rejection.

```java
assertThat(resolver.datasetIds(request)).containsExactlyInAnyOrder(datasetA, datasetB);
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -f backend/pom.xml -Dtest=QueryDatasetResolverTest test`

Expected: compilation failure because `QueryDatasetResolver` does not exist.

- [ ] **Step 3: Extract neutral reference resolution**

Move only object-to-dataset resolution and existence validation from `MetricAuthorizationService` into `QueryDatasetResolver`. Do not accept `PlatformPrincipal`, `QueryPermission`, or an allowed-dataset set.

- [ ] **Step 4: Make the catalog global**

Update `QueryController` as follows:

```java
return catalog.search(request, null);
```

For resolve, call `datasetResolver.requireResolve(request)` and `catalog.resolve(request, null)`. Remove checks from model/member search. For validation/execution, call `datasetResolver.datasetIds(request)` before query work and audit with `QueryOperation.VALIDATE` or `EXECUTE`. Always return the same query response; delete the role-based `withoutSql()` branch.

- [ ] **Step 5: Move audit naming out of authorization**

Move the writer to the query package, change its parameter from `QueryPermission` to `QueryOperation`, and remove obsolete `clientId` writes while retaining actor/effective-identity/outcome/request-correlation details.

- [ ] **Step 6: Verify GREEN**

Run: `mvn -f backend/pom.xml -Dtest=QueryDatasetResolverTest,QueryControllerTest test`

Expected: PASS with zero failures and zero errors.

---

### Task 5: Delete Obsolete Identity and Persistence Code

**Files:**

- Delete the admin controller/service, obsolete entities, mappers, persistence services, and grant mapper XML listed in the File Structure section.
- Modify: `backend/src/main/java/com/metricplatform/identity/PlatformIdentityAuditWriter.java`
- Modify: `backend/src/main/java/com/metricplatform/persistence/entity/AuditEventEntity.java`
- Modify: `backend/src/test/java/com/metricplatform/persistence/AuditEntityContractTest.java`
- Modify: `backend/src/test/java/com/metricplatform/persistence/AuditColumnsMigrationTest.java`
- Verify unchanged: `backend/src/test/java/com/metricplatform/persistence/PersistenceBoundaryTest.java`

**Interfaces:**

- The identity package exposes only login/me, bootstrap, rate limiting, properties, authentication service, DTOs, and audit support.
- The persistence layer maps no deleted table.

- [ ] **Step 1: Add/update architecture assertions first**

Update persistence tests to assert that `PlatformUserEntity` and `PlatformAccessTokenEntity` remain mapped while deleted entity classes are absent from the expected mapping set. Remove any `clientId` audit-field assertion and assert that `effectiveIdentityId`, `outcome`, and `detailJson` remain.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn -f backend/pom.xml -Dtest=MetricPlatformApplicationTest,PersistenceBoundaryTest,AuditEntityContractTest,AuditColumnsMigrationTest test`

Expected: FAIL until obsolete source types and audit client metadata are removed.

- [ ] **Step 3: Delete obsolete sources and simplify audit metadata**

Delete only the listed authorization/tenant/workspace/grant/API-client files. Remove `clientId` from `AuditEventEntity` and both audit writers. Keep account identity IDs and request correlation fields.

- [ ] **Step 4: Scan for stale authorization concepts**

Run:

```powershell
rg -n -i "PLATFORM_ADMIN|SEMANTIC_EDITOR|METRIC_QUERY_CLIENT|tenantId|tenant_id|workspaceIds|platform_workspace|role_code|roles|scopes|dataset.grant|discoverAllowed|queryAllowed|audience|clientId|client_id|MetricAuthorization|QueryPermission" backend/src/main frontend/src README.md
```

Expected: no production-code matches except historical Flyway migrations V1-V9 and an explicit README compatibility note.

- [ ] **Step 5: Verify GREEN**

Run: `mvn -f backend/pom.xml -Dtest=MetricPlatformApplicationTest,PersistenceBoundaryTest,AuditEntityContractTest,AuditColumnsMigrationTest test`

Expected: PASS with zero failures and zero errors.

---

### Task 6: Simplify the Frontend Authentication Contract

**Files:**

- Create: `frontend/src/api/authApi.test.ts`
- Modify: `frontend/src/api/auth.ts`
- Verify without unnecessary edits: `frontend/src/api/authApi.ts`
- Preserve existing changes: `frontend/src/auth/AuthenticationGuard.tsx`
- Preserve existing changes: `frontend/src/features/auth/LoginPage.tsx`

**Interfaces:**

- `AuthIdentity` contains `identityId`, `subject`, `displayName`, and `authVersion` only.
- Login storage and 401 redirect behavior remain unchanged.

- [ ] **Step 1: Add a failing auth API contract test**

Mock the login response with the simplified identity and assert the request body contains only username/password and the returned identity has no authorization fields:

```ts
expect(fetcher).toHaveBeenCalledWith(
  expect.stringContaining('/auth/token'),
  expect.objectContaining({ body: JSON.stringify({ username: 'admin', password: 'admin' }) }),
);
expect(session.identity).toEqual({
  identityId: 'user-1',
  subject: 'admin',
  displayName: 'Platform Administrator',
  authVersion: 2,
});
```

- [ ] **Step 2: Run the frontend test and verify RED**

Run: `npm --prefix frontend test -- authApi.test.ts`

Expected: type/build failure while `AuthIdentity` still requires tenant/workspace/role/scope fields.

- [ ] **Step 3: Simplify `AuthIdentity`**

Remove only `tenantId`, `workspaceIds`, `roles`, and `scopes`. Keep session persistence, safe return URLs, and expired-token redirection unchanged.

- [ ] **Step 4: Verify GREEN**

Run: `npm --prefix frontend test -- authApi.test.ts httpClient.test.ts PlatformLayout.test.tsx`

Expected: all selected Vitest tests pass.

---

### Task 7: Documentation, Full Verification, and Live Regression

**Files:**

- Modify: `README.md`
- Verify: all modified Metric Platform source and migration files.

**Interfaces:**

- README describes a global authenticated platform with no tenants or authorization.
- Runtime API returns existing semantic objects for `admin` without grants.

- [ ] **Step 1: Update documentation**

Replace role/scope/tenant/grant/bootstrap-tenant language with the global-account model. State that Data Agent is intentionally not modified and its old Metric Platform identity-field dependency is a breaking compatibility boundary.

- [ ] **Step 2: Format changed source**

Run: `mvn -f backend/pom.xml spotless:apply`

Run: `npm --prefix frontend run format`

Then inspect `git diff --stat` and `git diff --check`; ensure formatting did not alter unrelated user changes.

- [ ] **Step 3: Run the full backend suite and build**

Run: `mvn -f backend/pom.xml clean test`

Expected: all Metric Platform backend tests pass with zero failures/errors.

Run: `mvn -f backend/pom.xml -DskipTests package`

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the full frontend suite and build**

Run: `npm --prefix frontend test`

Expected: all Vitest tests pass.

Run: `npm --prefix frontend run build`

Expected: Prettier check, TypeScript build, and Vite build all succeed.

- [ ] **Step 5: Apply migration through the application and inspect live schema**

Restart only the Metric Platform backend using the freshly built artifact/current development command. Verify Flyway applies V10 successfully, then run read-only checks against `metric_platform`:

```sql
SELECT username, status, auth_version FROM platform_user WHERE username='admin';
SELECT count(*) FROM metric WHERE is_deleted=false;
SELECT count(*) FROM dimension WHERE is_deleted=false;
```

Expected: `admin` remains active; existing counts remain 7 metrics and 4 dimensions unless the user changed data during implementation.

- [ ] **Step 6: Verify the live authentication and query-workbench symptom**

Using `admin/admin`, obtain a token without printing it. Assert:

- login response `expiresIn` is `36000` and identity has no tenant/workspace/role/scope fields;
- unauthenticated `GET /api/v1/datasets` returns 401;
- authenticated `POST /api/v1/semantic-objects:search` returns the existing metrics/dimensions and a count greater than zero;
- authenticated management endpoint access is not rejected with 403;
- revoked/invalid token behavior still returns 401;
- the frontend at `http://localhost:5173/` redirects unauthenticated users to login and displays available query objects after login.

- [ ] **Step 7: Final scope and stale-concept audit**

Run `git status --short` from `metric-platform`, inspect every changed path, and confirm no Data Agent path changed. Re-run the stale-term scan and explain any remaining historical migration or compatibility-document matches.

Expected: only scoped Metric Platform changes, no unexpected user-file overwrite, and no live authorization filtering.
