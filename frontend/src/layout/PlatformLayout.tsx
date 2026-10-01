import {
  AppstoreOutlined,
  BarChartOutlined,
  FolderOpenOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import {
  Link,
  Outlet,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { clearAuthSession, getAuthSession } from '../api/auth';

const PROJECTS = '/dbt/projects';
const LOGIN = '/login?reason=required';
const COPY = {
  brand: 'Metric Platform',
  projects: '语义项目',
  catalog: '资源管理',
  query: '分析探索',
  logout: '登出',
  version: '固定版本',
  account: '当前账号',
};

export function PlatformLayout() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { projectId } = useParams();
  const [params] = useSearchParams();
  const releaseId = params.get('releaseId');
  const queryPage = pathname.endsWith('/query');
  const projectPath = projectId ? `${PROJECTS}/${encodeURIComponent(projectId)}` : PROJECTS;
  // 工作区切换携带当前版本与资源上下文；不会静默切换到最新发布。
  const catalogPath = `${projectPath}/catalog?${params}`;
  const queryPath = `${projectPath}/query?${params}`;
  const displayName = getAuthSession()?.identity.displayName;
  return (
    <div
      className={`platform-layout ${projectId ? 'platform-workbench' : 'platform-projects'} ${queryPage ? 'platform-analysis' : ''}`}
    >
      <nav className="platform-rail" aria-label={COPY.brand}>
        <Link className="platform-mark" to={PROJECTS} aria-label={COPY.brand}>
          MP
        </Link>
        <Tooltip title={COPY.projects} placement="right">
          <Link to={PROJECTS} className={!projectId ? 'is-active' : ''} aria-label={COPY.projects}>
            <FolderOpenOutlined />
          </Link>
        </Tooltip>
        {projectId && (
          <>
            <Tooltip title={COPY.catalog} placement="right">
              <Link
                to={catalogPath}
                className={!queryPage ? 'is-active' : ''}
                aria-label={COPY.catalog}
              >
                <AppstoreOutlined />
              </Link>
            </Tooltip>
            <Tooltip title={COPY.query} placement="right">
              <Link to={queryPath} className={queryPage ? 'is-active' : ''} aria-label={COPY.query}>
                <BarChartOutlined />
              </Link>
            </Tooltip>
          </>
        )}
        <div className="platform-rail-bottom">
          <Tooltip title={displayName ?? COPY.account} placement="right">
            <span className="platform-avatar">{displayName?.slice(0, 1).toUpperCase() ?? 'M'}</span>
          </Tooltip>
        </div>
      </nav>
      <div className="platform-main">
        <header className="platform-header">
          {projectId ? (
            <nav className="workspace-tabs" aria-label="工作区">
              <Link aria-current={!queryPage ? 'page' : undefined} to={catalogPath}>
                {COPY.catalog}
              </Link>
              <Link aria-current={queryPage ? 'page' : undefined} to={queryPath}>
                {COPY.query}
              </Link>
            </nav>
          ) : (
            <h1>{COPY.projects}</h1>
          )}
          <div className="platform-session">
            {releaseId && (
              <span className="platform-version" title={releaseId}>
                {COPY.version} <code>{releaseId.slice(0, 8)}</code>
              </span>
            )}
            <span className="platform-user">{displayName}</span>
            <Button
              type="text"
              icon={<LogoutOutlined />}
              aria-label={COPY.logout}
              onClick={() => {
                clearAuthSession();
                navigate(LOGIN, { replace: true });
              }}
            />
          </div>
        </header>
        <main className="platform-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
