import { Navigate, createBrowserRouter } from 'react-router-dom';
import { AuthenticationGuard } from './auth/AuthenticationGuard';
import { LoginPage } from './features/auth/LoginPage';
import { DataSourceCatalogPage } from './features/dataSources/DataSourceCatalogPage';
import { DatasetDetailPage } from './features/datasets/DatasetDetailPage';
import { DatasetEditorPage } from './features/datasets/DatasetEditorPage';
import { DatasetListPage } from './features/datasets/DatasetListPage';
import { DimensionListPage } from './features/dimensions/DimensionListPage';
import { MetricListPage } from './features/metrics/MetricListPage';
import { QueryWorkbenchPage } from './features/query/QueryWorkbenchPage';
import { PlatformLayout } from './layout/PlatformLayout';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <AuthenticationGuard />,
    children: [
      {
        element: <PlatformLayout />,
        children: [
          { index: true, element: <Navigate to="/data-sources" replace /> },
          { path: '/data-sources', element: <DataSourceCatalogPage /> },
          { path: '/datasets', element: <DatasetListPage /> },
          { path: '/datasets/new', element: <DatasetEditorPage /> },
          { path: '/datasets/:id', element: <DatasetDetailPage /> },
          { path: '/datasets/:id/edit', element: <DatasetEditorPage /> },
          { path: '/metrics', element: <MetricListPage /> },
          { path: '/dimensions', element: <DimensionListPage /> },
          { path: '/query', element: <QueryWorkbenchPage /> },
        ],
      },
    ],
  },
]);
