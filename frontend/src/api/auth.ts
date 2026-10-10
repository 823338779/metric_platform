const STORAGE_KEY = 'metric-platform.auth-session';
const DBT_PROJECTS_PATH = '/dbt/projects';
const FIXED_BUILD_PATH =
  /^\/dbt\/builds\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(catalog|query)$/i;

let redirecting = false;

export interface AuthIdentity {
  identityId: string;
  subject: string;
  displayName: string;
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
    return DBT_PROJECTS_PATH;
  const pathname = value.split(/[?#]/, 1)[0];
  // 仅放行实际固定构建页面，保留查询参数及片段，不扩大到任意内部或外部地址。
  return pathname === DBT_PROJECTS_PATH ||
    pathname.startsWith(`${DBT_PROJECTS_PATH}/`) ||
    FIXED_BUILD_PATH.test(pathname)
    ? value
    : DBT_PROJECTS_PATH;
}

export function redirectToLogin(reason: 'required' | 'expired' = 'expired') {
  clearAuthSession();
  if (redirecting || window.location.pathname === '/login') return;

  redirecting = true;
  const search = new URLSearchParams({ reason, returnTo: currentInternalLocation() });
  window.location.assign(`/login?${search}`);
}
