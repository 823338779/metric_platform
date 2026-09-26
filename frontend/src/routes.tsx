import { Navigate, createBrowserRouter } from 'react-router-dom';
import { AuthenticationGuard } from './auth/AuthenticationGuard';
import { LoginPage } from './features/auth/LoginPage';
import { DbtProjectsPage } from './features/dbt/DbtProjectsPage';
import { DbtCatalogPage } from './features/dbt/DbtCatalogPage';
import { DbtQueryPage } from './features/dbt/DbtQueryPage';
import { PlatformLayout } from './layout/PlatformLayout';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <AuthenticationGuard />,
    children: [
      {
        element: <PlatformLayout />,
        children: [
          { index: true, element: <Navigate to="/dbt/projects" replace /> },
          { path: '/dbt/projects', element: <DbtProjectsPage /> },
          { path: '/dbt/projects/:projectId/catalog', element: <DbtCatalogPage /> },
          { path: '/dbt/projects/:projectId/query', element: <DbtQueryPage /> },
          { path: '*', element: <Navigate to="/dbt/projects" replace /> },
        ],
      },
    ],
  },
]);
