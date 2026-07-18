# Metric Platform Password Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Do not dispatch subagents unless the user explicitly requests delegation.

**Goal:** Create a working local `PLATFORM_ADMIN` account and replace the Metric Platform frontend's fixed token with password login, session-scoped authentication, and centralized 401 recovery.

**Architecture:** Make usernames globally unique and let the backend derive tenant, roles, scopes, and workspaces from the account. Keep authentication in small frontend modules: session persistence owns `sessionStorage`, the authentication API owns `/auth/token` and `/auth/me`, a route guard validates restored sessions, and the shared HTTP client handles expired credentials. The backend continues issuing opaque database-backed tokens with an internal fixed audience and a 10-hour lifetime.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL, React 19, React Router 7, Ant Design 6, TypeScript 5.8, Vite 7.

## Global Constraints

- The login form and login request contain username and password only.
- Usernames are unique across the entire platform; tenant, roles, scopes, workspaces, and token audience are server-owned attributes.
- Persist the authentication session in `sessionStorage`, not `localStorage`.
- After reauthentication, return to the original internal page; reject external or protocol-relative return targets.
- Access tokens expire after exactly 10 hours (`PT10H`, `36000` seconds).
- Generate the administrator password at execution time and never write it to the repository.
- Preserve all existing dirty-worktree changes and modify only the files named in this plan.
- Do not add, delete, or modify test code.
- Do not create commits, branches, or pushes unless the user separately requests them.

---

### Task 1: Set the backend access-token lifetime to 10 hours

**Files:**
- Modify: `backend/src/main/java/com/metricplatform/identity/PlatformIdentityProperties.java:21-40`
- Modify: `backend/src/main/resources/application.yml:15-17`

**Interfaces:**
- Consumes: Spring property `metric-platform.identity-auth.token-ttl`.
- Produces: `PlatformIdentityProperties#getTokenTtl()` equal to `Duration.ofHours(10)` and login responses with `expiresIn == 36000`.

- [ ] **Step 1: Preserve the user's in-progress properties edit and make the narrow correction**

Change the existing `Duration.ofDays(10)` working-tree value to hours and update the validator:

```java
@NotNull private Duration tokenTtl = Duration.ofHours(10);

@AssertTrue(message = "Metric Platform access tokens must expire after exactly 10 hours")
public boolean isSupportedTokenTtl() {
    return Duration.ofHours(10).equals(tokenTtl);
}
```

- [ ] **Step 2: Update the runtime configuration**

```yaml
metric-platform:
  identity-auth:
    enabled: ${METRIC_PLATFORM_IDENTITY_AUTH_ENABLED:true}
    token-ttl: PT10H
```

- [ ] **Step 3: Compile the backend without modifying tests**

Run:

```powershell
.\mvnw.cmd -pl backend -DskipTests compile
```

Expected: Maven exits with code 0 and reports `BUILD SUCCESS`.

---

### Task 2: Make usernames globally unique and derive identity during login

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__global_platform_usernames.sql`
- Modify: `backend/src/main/java/com/metricplatform/identity/IdentityDtos.java:13-17`
- Modify: `backend/src/main/java/com/metricplatform/identity/LoginRateLimiter.java:26-67`
- Modify: `backend/src/main/java/com/metricplatform/identity/PlatformAuthenticationService.java:77-135`
- Modify: `backend/src/main/java/com/metricplatform/identity/PlatformIdentityAdminService.java:98-102`

**Interfaces:**
- Produces: `AuthTokenRequest(String username, String password)`.
- Produces: globally unique normalized `platform_user.username` values.
- Produces: login-derived tenant and authorization with backend-owned token audience `data-agent`.

- [ ] **Step 1: Add the global username migration**

Create `V9__global_platform_usernames.sql`:

```sql
UPDATE platform_user SET username = LOWER(TRIM(username));

ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS uq_platform_user_tenant_username;
ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS platform_user_username_key;
ALTER TABLE platform_user
    ADD CONSTRAINT uq_platform_user_username UNIQUE (username);
```

The update and unique constraint must fail the migration if existing accounts normalize to the same username; do not merge, rename, or delete accounts automatically.

- [ ] **Step 2: Simplify the login request DTO**

Replace the four-field request with:

```java
public record AuthTokenRequest(
        @NotBlank String username, @NotBlank @Size(max = 1024) String password) {}
```

- [ ] **Step 3: Remove tenant from the login rate-limit key**

Change `checkAllowed`, `recordFailure`, and `recordSuccess` to accept `(String remoteAddress, String username)`. Build the hashed key from only the normalized remote address and username separated by a newline.

- [ ] **Step 4: Resolve the account before the tenant in `PlatformAuthenticationService`**

Keep `AUDIENCE = "data-agent"` as an internal constant. In `login`:

```java
if (!properties.isEnabled()) throw invalidCredentials();
String username = normalize(request.username());
rateLimiter.checkAllowed(remoteAddress, username);

PlatformUserEntity user =
        users.getOne(
                new LambdaQueryWrapper<PlatformUserEntity>()
                        .eq(PlatformUserEntity::getUsername, username));
PlatformTenantEntity tenant = user == null ? null : tenants.getById(user.getTenantId());
```

Preserve dummy-password matching, account status, tenant status, lockout, token persistence, and audit-safe error behavior. Update limiter success/failure calls to the new two-argument API. Never accept tenant, roles, scopes, workspaces, or audience from the request.

- [ ] **Step 5: Update the global duplicate-username conflict message**

In `PlatformIdentityAdminService#createUser`, change the duplicate response to:

```java
throw new ConflictException("Platform username already exists");
```

- [ ] **Step 6: Compile the backend and validate the migration against the local database**

Run:

```powershell
.\mvnw.cmd -pl backend -DskipTests compile
```

Expected: Maven exits with code 0. On the next backend start, Flyway applies V9 successfully and PostgreSQL reports `uq_platform_user_username` on `platform_user(username)`.

---

### Task 3: Replace the fixed frontend token with session-backed authentication

**Files:**
- Modify: `frontend/src/api/auth.ts`
- Create: `frontend/src/api/authApi.ts`

**Interfaces:**
- Produces: `AuthIdentity`, `AuthSession`, `getAuthSession()`, `getAccessToken()`, `saveAuthSession()`, `clearAuthSession()`, `currentInternalLocation()`, `safeReturnTo()`, and `redirectToLogin()`.
- Produces: `authApi.login(username, password)` and `authApi.me()`.
- Consumes later: `httpClient.ts`, `AuthenticationGuard.tsx`, and `LoginPage.tsx`.

- [ ] **Step 1: Replace `frontend/src/api/auth.ts` with the session contract**

Implement the following behavior:

```typescript
const STORAGE_KEY = 'metric-platform.auth-session';
let redirecting = false;

export interface AuthIdentity {
  identityId: string;
  subject: string;
  displayName: string;
  tenantId: string;
  workspaceIds: string[];
  roles: string[];
  scopes: string[];
  authVersion: number;
}

export interface AuthSession {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  identity: AuthIdentity;
}

export function getAuthSession(): AuthSession | null {
  const stored = sessionStorage.getItem(STORAGE_KEY);
  if (!stored) return null;
  try {
    const session = JSON.parse(stored) as AuthSession;
    return session.accessToken?.trim() ? session : null;
  } catch {
    sessionStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

export function getAccessToken() {
  return getAuthSession()?.accessToken ?? null;
}

export function saveAuthSession(session: AuthSession) {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  redirecting = false;
}

export function clearAuthSession() {
  sessionStorage.removeItem(STORAGE_KEY);
}

export function currentInternalLocation() {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

export function safeReturnTo(value: string | null) {
  return value?.startsWith('/') && !value.startsWith('//') ? value : '/data-sources';
}

export function redirectToLogin(reason: 'required' | 'expired' = 'expired') {
  clearAuthSession();
  if (redirecting || window.location.pathname === '/login') return;
  redirecting = true;
  const search = new URLSearchParams({ reason, returnTo: currentInternalLocation() });
  window.location.assign(`/login?${search}`);
}
```

- [ ] **Step 2: Create the authentication API module**

Implement `frontend/src/api/authApi.ts` with a private request helper that does not invoke the global 401 redirect:

```typescript
import type { AuthIdentity, AuthSession } from './auth';
import { getAccessToken } from './auth';
import { ApiProblem, type Fetcher } from './httpClient';
import type { ProblemDetails } from './types';

const baseUrl = import.meta.env.VITE_METRIC_PLATFORM_API_URL ?? '/api/v1';

async function readProblem(response: Response): Promise<ApiProblem> {
  let problem: ProblemDetails = {
    title: 'Request failed',
    status: response.status,
    detail: `Request failed (${response.status})`,
  };
  try {
    problem = { ...problem, ...((await response.json()) as ProblemDetails) };
  } catch {
    // Use the sanitized fallback.
  }
  return new ApiProblem(problem);
}

export function createAuthApi(fetcher: Fetcher = fetch) {
  return {
    async login(username: string, password: string): Promise<AuthSession> {
      const response = await fetcher(`${baseUrl}/auth/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      if (!response.ok) throw await readProblem(response);
      return response.json() as Promise<AuthSession>;
    },
    async me(): Promise<AuthIdentity> {
      const response = await fetcher(`${baseUrl}/auth/me`, {
        headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
      });
      if (!response.ok) throw await readProblem(response);
      return response.json() as Promise<AuthIdentity>;
    },
  };
}

export const authApi = createAuthApi();
```

- [ ] **Step 3: Run the frontend formatter on the two authentication files**

Run:

```powershell
npx prettier --write src/api/auth.ts src/api/authApi.ts
```

Expected: both files are formatted successfully.

---

### Task 4: Centralize 401 handling in the shared HTTP client

**Files:**
- Modify: `frontend/src/api/httpClient.ts:1-42`

**Interfaces:**
- Consumes: `getAccessToken()` and `redirectToLogin()` from `auth.ts`.
- Produces: all Metric Platform business API calls automatically attach the current session token and redirect once on HTTP 401.

- [ ] **Step 1: Replace the fixed authenticated header import**

Use:

```typescript
import { getAccessToken, redirectToLogin } from './auth';
```

Construct headers for each request so token changes are immediately visible:

```typescript
const token = getAccessToken();
const headers: Record<string, string> = {
  'Content-Type': 'application/json',
  ...(init.headers as Record<string, string> | undefined),
};
if (token) headers.Authorization = `Bearer ${token}`;

const response = await fetcher(baseUrl + path, { ...init, headers });
```

- [ ] **Step 2: Handle unauthorized responses before parsing the problem body**

```typescript
if (response.status === 401) {
  redirectToLogin('expired');
}
```

Keep the existing sanitized `ApiProblem` parsing for every non-2xx response, including 401 and 403.

- [ ] **Step 3: Format and type-check the client**

Run:

```powershell
npx prettier --write src/api/httpClient.ts
npx tsc -b --pretty false
```

Expected: both commands exit with code 0.

---

### Task 5: Add the login page and authentication route guard

**Files:**
- Create: `frontend/src/auth/AuthenticationGuard.tsx`
- Create: `frontend/src/features/auth/LoginPage.tsx`
- Modify: `frontend/src/routes.tsx`
- Modify: `frontend/src/global.css`

**Interfaces:**
- `AuthenticationGuard` consumes `getAuthSession()`, `authApi.me()`, and React Router location state.
- `LoginPage` consumes `authApi.login()`, `saveAuthSession()`, and `safeReturnTo()`.
- The router exposes public `/login` and protects all current platform routes.

- [ ] **Step 1: Create `AuthenticationGuard`**

The guard must:

1. Read the saved session once when mounted.
2. Navigate to `/login?reason=required&returnTo=<current internal location>` when no session exists.
3. Call `authApi.me()` when a session exists.
4. Render a centered Ant Design `Spin` while validating.
5. Render `<Outlet />` after successful validation.
6. On validation failure, clear the session and navigate to the login page with `reason=expired`.
7. Cancel state updates after unmount.

Use `Navigate`, `Outlet`, and `useLocation` from React Router. Build the return address from `pathname`, `search`, and `hash`, then encode it through `URLSearchParams`.

- [ ] **Step 2: Create `LoginPage`**

Use an Ant Design `Form`, `Input`, `Input.Password`, `Button`, `Alert`, `Card`, and `Typography`.

Required behavior:

```typescript
const returnTo = safeReturnTo(searchParams.get('returnTo'));
const reason = searchParams.get('reason');

async function submit(values: { username: string; password: string }) {
  setSubmitting(true);
  setError(null);
  try {
    const session = await authApi.login(values.username.trim(), values.password);
    saveAuthSession(session);
    navigate(returnTo, { replace: true });
  } catch (failure) {
    setError(
      failure instanceof ApiProblem && failure.problem.status === 401
        ? '账号或密码错误'
        : '登录失败，请确认服务可用后重试',
    );
  } finally {
    setSubmitting(false);
  }
}
```

Display `登录已过期，请重新登录` when `reason === 'expired'`. Username and password are required; set `autoComplete="username"` and `autoComplete="current-password"` respectively.

- [ ] **Step 3: Restructure the router**

Use this route hierarchy:

```tsx
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <AuthenticationGuard />,
    children: [
      {
        element: <PlatformLayout />,
        children: [
          { index: true, element: <Navigate to="/data-sources" replace /> },
          // Preserve every existing platform route unchanged.
        ],
      },
    ],
  },
]);
```

- [ ] **Step 4: Add narrowly scoped login styles**

Add `.login-page`, `.login-card`, `.login-brand`, and `.authentication-loading` rules to `frontend/src/global.css`. Use a full-height light neutral background, a card width capped at `420px`, and responsive horizontal padding. Do not restyle existing feature pages.

- [ ] **Step 5: Format and build the frontend**

Run:

```powershell
npx prettier --write src/auth/AuthenticationGuard.tsx src/features/auth/LoginPage.tsx src/routes.tsx src/global.css
npm run build
```

Expected: Prettier check, TypeScript project build, and Vite production build all exit with code 0.

---

### Task 6: Bootstrap the local administrator and verify the complete flow

**Files:**
- No repository files. Runtime and database operations only.

**Interfaces:**
- Produces database user `default/admin` with role `PLATFORM_ADMIN`.
- Produces a one-time initial password delivered to the user.
- Verifies backend login returns `expiresIn: 36000` and the frontend can recover from an invalid token.

- [ ] **Step 1: Generate a strong one-time password without writing it to disk**

In one PowerShell session, create 24 random bytes using `RandomNumberGenerator`, Base64-encode them, and replace `/`, `+`, and `=` with URL-safe characters. Keep the value only in the process variable used for bootstrap and final delivery.

- [ ] **Step 2: Start the modified backend with one-time bootstrap variables**

Temporarily set:

```text
METRIC_PLATFORM_BOOTSTRAP_ENABLED=true
METRIC_PLATFORM_BOOTSTRAP_TENANT_KEY=default
METRIC_PLATFORM_BOOTSTRAP_USERNAME=admin
METRIC_PLATFORM_BOOTSTRAP_DISPLAY_NAME=Platform Administrator
METRIC_PLATFORM_BOOTSTRAP_PASSWORD=<generated value>
```

Start the backend on an available local port if port 8090 is occupied. Wait for the process to accept HTTP requests. Do not add these values to `.env`, IDE run configurations, shell profiles, or repository files.

- [ ] **Step 3: Verify the account and 10-hour token**

POST this body to `/api/v1/auth/token`:

```json
{
  "username": "admin",
  "password": "<generated runtime value>"
}
```

Expected: HTTP 200, the derived tenant is `default`, role includes `PLATFORM_ADMIN`, and `expiresIn` equals `36000`. Use the returned token to call `/api/v1/auth/me` and `/api/v1/data-sources`; both must return HTTP 200.

- [ ] **Step 4: Leave the normal backend available on port 8090**

Stop only the temporary bootstrap process if a temporary port was used. Restart the modified backend normally on port 8090 without bootstrap secrets, or confirm the user's existing backend process has been restarted with the modified classes. Verify `/api/v1/auth/token` still accepts the generated password.

- [ ] **Step 5: Verify the browser workflow**

At `http://localhost:5173/` verify:

1. An unauthenticated protected URL redirects to `/login`.
2. `admin` and the generated password return to the original URL.
3. Refresh preserves the session.
4. Replacing the stored token with an invalid value and triggering a business request redirects to `/login?reason=expired...`.
5. Logging in again returns to the page that encountered 401.
6. A wrong password shows an inline error and remains on `/login`.
7. A request containing legacy `tenantKey` or `audience` fields does not influence the resolved tenant or permissions; normal frontend requests omit both fields.

- [ ] **Step 6: Run final verification and inspect the exact diff**

Run:

```powershell
.\mvnw.cmd -pl backend -DskipTests compile
Set-Location frontend
npm run build
Set-Location ..
git diff --check
git status --short
```

Expected: both builds pass, `git diff --check` reports no whitespace errors, and only the planned production files plus the approved design/plan documents are new or modified by this work.

---

## Plan Self-Review

- Every design requirement maps to Tasks 1-6.
- The plan contains no test-file changes and no credential persistence.
- Frontend session and API type names are consistent across tasks.
- Runtime bootstrap uses the backend's supported path rather than direct SQL insertion.
- Database, DTO, limiter, authentication service, and administrator creation all enforce the global username model consistently.
- Existing unrelated working-tree changes remain untouched.
