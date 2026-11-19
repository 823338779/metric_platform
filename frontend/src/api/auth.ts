const STORAGE_KEY = 'metric-platform.auth-session';
const DEFAULT_PATH = '/dbt/environments';
// 登录后保留环境列表或指定环境，限制为实际存在的路由。
const ENVIRONMENT_PATH =
  /^\/dbt\/environments(?:\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$/i;
const FIXED_BUILD_PATH =
  /^\/dbt\/builds\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(catalog|query)$/i;

let redirecting = false;

export interface AuthIdentity {
  identityId: string;
  subject: string;
  displayName: string;
  authVersion: number;
  /** 登录身份当前所属租户与资产访问角色。 */
  tenant_id: string;
  role: 'TENANT_MANAGER' | 'READ_ONLY';
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
    if (session.accessToken?.trim()) return session;
  } catch {
    // Invalid session data is cleared below.
  }

  sessionStorage.removeItem(STORAGE_KEY);
  return null;
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
  if (!value?.startsWith('/') || value.startsWith('//') || value.startsWith('/\\'))
    return DEFAULT_PATH;
  const pathname = value.split(/[?#]/, 1)[0];
  // 仅放行实际固定构建页面，保留查询参数及片段，不扩大到任意内部或外部地址。
  return pathname === '/tenant/accounts' ||
    ENVIRONMENT_PATH.test(pathname) ||
    FIXED_BUILD_PATH.test(pathname)
    ? value
    : DEFAULT_PATH;
}

export function redirectToLogin(reason: 'required' | 'expired' = 'expired') {
  clearAuthSession();
  if (redirecting || window.location.pathname === '/login') return;

  redirecting = true;
  const search = new URLSearchParams({ reason, returnTo: currentInternalLocation() });
  window.location.assign(`/login?${search}`);
}
