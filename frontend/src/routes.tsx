import { Navigate, createBrowserRouter } from 'react-router-dom';
import { AuthenticationGuard } from './auth/AuthenticationGuard';
import { LoginPage } from './features/auth/LoginPage';
import { PlatformLayout } from './layout/PlatformLayout';
import { PublishedWorkbench } from './features/dbt/PublishedWorkbench';
import { PublishedCatalogPage } from './features/dbt/PublishedCatalogPage';
import { EnvironmentsPage } from './features/environments/EnvironmentsPage';
import { EnvironmentDetailPage } from './features/environments/EnvironmentDetailPage';

/** 所有目录和查询路由统一使用构建契约，无发布模式开关。 */
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <AuthenticationGuard />,
    children: [
      {
        element: <PlatformLayout />,
        children: [
          { index: true, element: <Navigate to="/dbt/environments" replace /> },
          { path: '/dbt/environments', element: <EnvironmentsPage /> },
          { path: '/dbt/environments/:environmentId', element: <EnvironmentDetailPage /> },
          { path: '/dbt/builds/:buildId/catalog', element: <PublishedCatalogPage /> },
          { path: '/dbt/builds/:buildId/query', element: <PublishedWorkbench /> },
          { path: '*', element: <Navigate to="/dbt/environments" replace /> },
        ],
      },
    ],
  },
]);
