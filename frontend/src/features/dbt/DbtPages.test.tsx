import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { DbtProjectsPage } from './DbtProjectsPage';
import { DbtCatalogPage } from './DbtCatalogPage';
import { DbtQueryPage } from './DbtQueryPage';
import { fireEvent, waitFor } from '@testing-library/react';
import { ApiProblem } from '../../api/httpClient';

afterEach(cleanup);
beforeAll(() => {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  window.matchMedia = vi.fn().mockImplementation(() => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
});

it('shows observed and published project state separately', async () => {
  const api = {
    listDbtProjects: vi.fn().mockResolvedValue([
      {
        projectId: 'demo',
        observedSha: 'new-sha',
        activeReleaseId: 'release-1',
        lastObservationStatus: 'CANDIDATE',
      },
    ]),
  };
  render(
    <MemoryRouter>
      <DbtProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  expect(await screen.findByText('new-sha')).toBeTruthy();
  expect(screen.getByText('release-1')).toBeTruthy();
});

it('renders a read-only catalog pinned to its response release', async () => {
  const api = {
    getDbtCatalog: vi.fn().mockResolvedValue({
      releaseId: 'release-1',
      page: 0,
      size: 20,
      total: 1,
      resources: [
        {
          resourceId: 'metric.revenue',
          nativeId: 'metric.revenue',
          kind: 'METRIC',
          name: 'Revenue',
          sourcePath: 'models/metrics.yml',
          definitionJson: '{}',
          queryable: true,
        },
      ],
    }),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?releaseId=release-1']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('Revenue')).toBeTruthy();
  expect(screen.getByText('releaseId：release-1')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /编辑|删除|新建/ })).toBeNull();
});

it('queries the selected release and renders exact decimal strings', async () => {
  const api = {
    listDbtProjects: vi
      .fn()
      .mockResolvedValue([{ projectId: 'demo', activeReleaseId: 'release-1' }]),
    getDbtCatalog: vi.fn().mockResolvedValue({
      releaseId: 'release-1',
      resources: [
        { resourceId: 'metric.revenue', kind: 'METRIC', name: 'revenue', queryable: true },
      ],
    }),
    getDbtQueryOptions: vi.fn().mockResolvedValue({
      releaseId: 'release-1',
      metrics: [{ name: 'revenue' }],
      dimensions: [],
      timeDimensions: [],
      allowedFilters: ['='],
    }),
    submitDbtQuery: vi
      .fn()
      .mockResolvedValue({ queryId: 'query-1', releaseId: 'release-1', status: 'PENDING' }),
    explainDbtQuery: vi.fn(),
    getDbtQuery: vi.fn().mockResolvedValue({
      queryId: 'query-1',
      releaseId: 'release-1',
      targetCommitSha: 'sha',
      mode: 'QUERY',
      status: 'COMPLETED',
      columns: [{ name: 'revenue', type: 'DECIMAL(38,2)' }],
      rows: [['12345678901234567890.12']],
      errorCode: null,
    }),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/query?releaseId=release-1&metric=revenue']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/query"
          element={<DbtQueryPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: '运行查询' }));
  await waitFor(() =>
    expect(api.submitDbtQuery).toHaveBeenCalledWith(
      'demo',
      expect.objectContaining({ releaseId: 'release-1', metrics: ['revenue'] }),
    ),
  );
  expect(await screen.findByText('12345678901234567890.12')).toBeTruthy();
});

it('explains a cleaned catalog release', async () => {
  const api = {
    getDbtCatalog: vi
      .fn()
      .mockRejectedValue(new ApiProblem({ title: 'Gone', status: 410, detail: 'cleaned' })),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?releaseId=old']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('该发布版本仅保留发布记录')).toBeTruthy();
});
