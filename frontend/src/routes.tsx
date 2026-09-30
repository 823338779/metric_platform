import { Navigate, createBrowserRouter } from 'react-router-dom';
import { AuthenticationGuard } from './auth/AuthenticationGuard';
import { LoginPage } from './features/auth/LoginPage';
import { DbtProjectsPage } from './features/dbt/DbtProjectsPage';
import { DbtCatalogPage } from './features/dbt/DbtCatalogPage';
import { DbtQueryPage } from './features/dbt/DbtQueryPage';
import { PlatformLayout } from './layout/PlatformLayout';
import { PublishedProjectsPage } from './features/dbt/PublishedProjectsPage';
import { PublishedWorkbench } from './features/dbt/PublishedWorkbench';

// 部署窗口与后端 service-proxy 同步切换，旧入口仅供迁移前使用。
const SERVICE_PROXY = import.meta.env.VITE_PUBLICATION_MODE === 'service-proxy';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <AuthenticationGuard />,
    children: [
      {
        element: <PlatformLayout />,
        children: [
          { index: true, element: <Navigate to="/dbt/projects" replace /> },
          {
            path: '/dbt/projects',
            element: SERVICE_PROXY ? <PublishedProjectsPage /> : <DbtProjectsPage />,
          },
          {
            path: '/dbt/projects/:projectId/catalog',
            element: SERVICE_PROXY ? <PublishedWorkbench /> : <DbtCatalogPage />,
          },
          {
            path: '/dbt/projects/:projectId/query',
            element: SERVICE_PROXY ? <PublishedWorkbench /> : <DbtQueryPage />,
          },
          { path: '*', element: <Navigate to="/dbt/projects" replace /> },
        ],
      },
    ],
  },
]);
