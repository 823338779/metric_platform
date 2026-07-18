import {
  ApartmentOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  FieldTimeOutlined,
  LineChartOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { Button, Layout, Menu, Space, Typography } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { clearAuthSession, getAuthSession } from '../api/auth';

export function PlatformLayout() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const selected =
    ['/data-sources', '/datasets', '/metrics', '/dimensions', '/query'].find((path) =>
      pathname.startsWith(path),
    ) ?? '/data-sources';
  const displayName = getAuthSession()?.identity.displayName;
  const handleLogout = () => {
    clearAuthSession();
    navigate('/login?reason=required', { replace: true });
  };
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider breakpoint="lg" collapsedWidth="0" theme="dark">
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '0 20px',
            color: '#fff',
          }}
        >
          <ApartmentOutlined />
          <Typography.Text style={{ color: '#fff', fontWeight: 700 }}>
            Metric Platform
          </Typography.Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selected]}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: '/data-sources', icon: <CloudServerOutlined />, label: '数据源' },
            { key: '/datasets', icon: <DatabaseOutlined />, label: '数据集' },
            { key: '/metrics', icon: <LineChartOutlined />, label: '指标' },
            { key: '/dimensions', icon: <FieldTimeOutlined />, label: '维度' },
            { key: '/query', icon: <PlayCircleOutlined />, label: '查询工作台' },
          ]}
        />
      </Layout.Sider>
      <Layout style={{ minWidth: 0 }}>
        <Layout.Header
          style={{
            background: '#fff',
            padding: '0 24px',
            borderBottom: '1px solid #f0f0f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <Typography.Title level={4} style={{ margin: 0 }}>
            语义指标平台
          </Typography.Title>
          <Space>
            {displayName ? <Typography.Text>{displayName}</Typography.Text> : null}
            <Button onClick={handleLogout}>登出</Button>
          </Space>
        </Layout.Header>
        <Layout.Content style={{ padding: 24, background: '#f5f7fa', minWidth: 0 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
