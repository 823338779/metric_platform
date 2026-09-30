import {
  ApartmentOutlined,
  FolderOpenOutlined,
  AppstoreOutlined,
  BarChartOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Button, Layout, Menu, Space, Typography } from 'antd';
import { Outlet, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { clearAuthSession, getAuthSession } from '../api/auth';

const PROJECTS = '/dbt/projects';
const LOGIN = '/login?reason=required';
const COPY = {
  brand: 'Metric Platform',
  subtitle: '语义指标平台',
  workspace: '工作空间',
  projects: '语义项目',
  catalog: '目录',
  query: '探索',
  logout: '登出',
  versioned: '定义 · 血缘 · 探索',
};

export function PlatformLayout() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { projectId } = useParams();
  const [params] = useSearchParams();
  const releaseId = params.get('releaseId');
  const projectPath = projectId ? `${PROJECTS}/${encodeURIComponent(projectId)}` : '';
  const catalogPath = `${projectPath}/catalog?releaseId=${encodeURIComponent(releaseId ?? '')}`;
  const queryPath = `${projectPath}/query?releaseId=${encodeURIComponent(releaseId ?? '')}`;
  const selected = projectId ? (pathname.endsWith('/query') ? queryPath : catalogPath) : PROJECTS;
  const displayName = getAuthSession()?.identity.displayName;
  const handleLogout = () => {
    clearAuthSession();
    navigate(LOGIN, { replace: true });
  };
  return (
    <Layout className="platform-layout">
      <Layout.Sider
        className="platform-sidebar"
        width={204}
        breakpoint="lg"
        collapsedWidth="0"
        theme="light"
      >
        <div className="platform-brand">
          <span className="platform-brand-icon">
            <ApartmentOutlined />
          </span>
          <Typography.Text strong>{COPY.brand}</Typography.Text>
        </div>
        <div className="platform-nav-label">{COPY.workspace}</div>
        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selected]}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: PROJECTS, icon: <FolderOpenOutlined />, label: COPY.projects },
            ...(projectId && releaseId
              ? [
                  { key: catalogPath, icon: <AppstoreOutlined />, label: COPY.catalog },
                  { key: queryPath, icon: <BarChartOutlined />, label: COPY.query },
                ]
              : []),
          ]}
        />
        <div className="platform-sidebar-footer">{COPY.versioned}</div>
      </Layout.Sider>
      <Layout style={{ minWidth: 0 }}>
        <Layout.Header className="platform-header">
          <div className="platform-header-title">
            {COPY.subtitle}
            {projectId && <span className="platform-project-name"> / {projectId}</span>}
          </div>
          <Space>
            {displayName ? (
              <Space className="platform-user">
                <UserOutlined />
                <Typography.Text>{displayName}</Typography.Text>
              </Space>
            ) : null}
            <Button type="text" onClick={handleLogout}>
              {COPY.logout}
            </Button>
          </Space>
        </Layout.Header>
        <Layout.Content className="platform-content">
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
