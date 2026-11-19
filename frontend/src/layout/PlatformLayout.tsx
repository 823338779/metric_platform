import {
  AppstoreOutlined,
  BarChartOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  DatabaseOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import { useState } from 'react';
import {
  Link,
  Outlet,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { clearAuthSession, getAuthSession } from '../api/auth';
import { ACCOUNTS_PATH } from '../features/accounts/TenantAccountsPage';

const ENVIRONMENTS = '/dbt/environments';
const LOGIN = '/login?reason=required';
const COMPACT_NAVIGATION = '(max-width: 1199px)';
const COPY = {
  brand: 'Metric Platform',
  environments: '执行环境',
  catalog: '资源管理',
  query: '分析探索',
  logout: '登出',
  version: '固定版本',
  account: '当前账号',
  collapse: '收起导航',
  expand: '展开导航',
  management: '管理',
  workspace: '工作区',
  home: '指标工作台',
  hint: '选择一个构建版本，开始浏览资源与分析数据。',
};

export function PlatformLayout() {
  const [collapsed, setCollapsed] = useState(
    () => window.matchMedia?.(COMPACT_NAVIGATION).matches ?? false,
  );
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { buildId: routeBuildId } = useParams();
  const [params] = useSearchParams();
  const buildId = routeBuildId ?? params.get('buildId');
  const queryPage = pathname.endsWith('/query');
  const projectPath = buildId ? `/dbt/builds/${encodeURIComponent(buildId)}` : ENVIRONMENTS;
  // 工作区切换携带当前版本与资源上下文；不会静默切换到最新发布。
  const catalogPath = `${projectPath}/catalog?${params}`;
  const queryPath = `${projectPath}/query?${params}`;
  const identity = getAuthSession()?.identity;
  const displayName = identity?.displayName;
  const manager = identity?.role === 'TENANT_MANAGER';
  return (
    <div
      className={`platform-layout ${collapsed ? 'nav-collapsed' : ''} ${buildId ? 'platform-workbench' : 'platform-projects'} ${queryPage ? 'platform-analysis' : 'platform-catalog'}`}
    >
      <nav className="platform-rail" aria-label={COPY.brand}>
        <Link className="platform-mark" to={ENVIRONMENTS} aria-label={COPY.brand}>
          <span className="platform-monogram">
            M<span>.</span>
          </span>
          <span className="platform-brand">
            Metric<span>Platform</span>
          </span>
        </Link>
        <span className="platform-nav-label">{COPY.management}</span>
        {manager && (
          <Tooltip title="账号管理" placement="right">
            <Link
              to={ACCOUNTS_PATH}
              className={pathname === ACCOUNTS_PATH ? 'is-active' : ''}
              aria-label="账号管理"
              aria-current={pathname === ACCOUNTS_PATH ? 'page' : undefined}
            >
              <TeamOutlined aria-hidden />
              <span className="platform-nav-text">账号管理</span>
            </Link>
          </Tooltip>
        )}
        <Tooltip title={COPY.environments} placement="right">
          <Link
            to={ENVIRONMENTS}
            className={pathname.startsWith(ENVIRONMENTS) ? 'is-active' : ''}
            aria-label={COPY.environments}
            aria-current={pathname.startsWith(ENVIRONMENTS) ? 'page' : undefined}
          >
            <DatabaseOutlined aria-hidden />
            <span className="platform-nav-text">{COPY.environments}</span>
          </Link>
        </Tooltip>
        {buildId && (
          <>
            <span className="platform-nav-label">{COPY.workspace}</span>
            <Tooltip title={COPY.catalog} placement="right">
              <Link
                to={catalogPath}
                className={!queryPage ? 'is-active' : ''}
                aria-label={COPY.catalog}
                aria-current={!queryPage ? 'page' : undefined}
              >
                <AppstoreOutlined aria-hidden />
                <span className="platform-nav-text">{COPY.catalog}</span>
              </Link>
            </Tooltip>
            <Tooltip title={COPY.query} placement="right">
              <Link
                to={queryPath}
                className={queryPage ? 'is-active' : ''}
                aria-label={COPY.query}
                aria-current={queryPage ? 'page' : undefined}
              >
                <BarChartOutlined aria-hidden />
                <span className="platform-nav-text">{COPY.query}</span>
              </Link>
            </Tooltip>
          </>
        )}
        <div className="platform-rail-bottom">
          {!buildId && <p className="platform-nav-hint">{COPY.hint}</p>}
          <Tooltip title={displayName ?? COPY.account} placement="right">
            <span className="platform-avatar">{displayName?.slice(0, 1).toUpperCase() ?? 'M'}</span>
          </Tooltip>
          <span className="platform-nav-text">{COPY.home}</span>
        </div>
      </nav>
      <div className="platform-main">
        <header className="platform-header">
          <Button
            type="text"
            className="platform-nav-toggle"
            aria-label={collapsed ? COPY.expand : COPY.collapse}
            aria-expanded={!collapsed}
            icon={collapsed ? <MenuUnfoldOutlined aria-hidden /> : <MenuFoldOutlined aria-hidden />}
            onClick={() => setCollapsed((value) => !value)}
          />
          {buildId ? (
            <nav className="workspace-tabs" aria-label="工作区">
              <Link aria-current={!queryPage ? 'page' : undefined} to={catalogPath}>
                {COPY.catalog}
              </Link>
              <Link aria-current={queryPage ? 'page' : undefined} to={queryPath}>
                {COPY.query}
              </Link>
            </nav>
          ) : (
            <span className="platform-header-label">{COPY.home}</span>
          )}
          <div className="platform-session">
            {buildId && (
              <span className="platform-version" title={buildId}>
                {COPY.version} <code>{buildId.slice(0, 8)}</code>
              </span>
            )}
            <span className="platform-user">{displayName}</span>
            <Button
              type="text"
              icon={<LogoutOutlined aria-hidden />}
              aria-label={COPY.logout}
              onClick={() => {
                clearAuthSession();
                navigate(LOGIN, { replace: true });
              }}
            />
          </div>
        </header>
        <main className="platform-content">
          <Outlet key={identity?.identityId} />
        </main>
      </div>
    </div>
  );
}
