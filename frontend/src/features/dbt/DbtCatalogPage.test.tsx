import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeAll, afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { DbtCatalogPage } from './DbtCatalogPage';

beforeAll(() => {
  globalThis.ResizeObserver = class {
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
afterEach(cleanup);

function SwitchRelease() {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate('/dbt/projects/demo/catalog?releaseId=B')}>切换版本</button>
  );
}

it('groups resources and searches only within the pinned release', async () => {
  const api = {
    getDbtCatalog: vi.fn().mockResolvedValue({
      releaseId: 'A',
      page: 0,
      size: 20,
      total: 2,
      resources: [
        { resourceId: 'm', kind: 'METRIC', name: 'revenue', queryable: true },
        { resourceId: 't', kind: 'TABLE', name: 'orders', queryable: true },
      ],
    }),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?releaseId=A']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('revenue')).toBeTruthy();
  expect(screen.getByText('指标')).toBeTruthy();
  expect(screen.getByText('数据表')).toBeTruthy();
  fireEvent.change(screen.getByPlaceholderText('搜索资源'), { target: { value: 'orders' } });
  fireEvent.click(screen.getByRole('button', { name: 'search' }));
  await waitFor(() =>
    expect(api.getDbtCatalog).toHaveBeenLastCalledWith('demo', 'A', 'orders', 0, 20, undefined),
  );
});

it('asks the server to filter resource kinds before pagination', async () => {
  const api = {
    getDbtCatalog: vi
      .fn()
      .mockImplementation(
        (_: string, __: string, ___: string, ____: number, _____: number, kind?: string) =>
          Promise.resolve({
            releaseId: 'A',
            page: 0,
            size: 20,
            total: kind ? 1 : 21,
            resources: kind
              ? [{ resourceId: 'm', kind: 'METRIC', name: 'later metric', queryable: true }]
              : [{ resourceId: 't', kind: 'TABLE', name: 'first table', queryable: true }],
          }),
      ),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?releaseId=A']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('first table')).toBeTruthy();
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '资源类型' }));
  fireEvent.click(await screen.findByText('指标'));
  expect(await screen.findByText('later metric')).toBeTruthy();
  expect(api.getDbtCatalog).toHaveBeenLastCalledWith('demo', 'A', '', 0, 20, 'METRIC');
});

it('clears old detail and ignores the old catalog response after release switch', async () => {
  let finishOld: ((value: unknown) => void) | undefined;
  const api = {
    getDbtCatalog: vi.fn().mockImplementation((_: string, release: string) =>
      release === 'A'
        ? new Promise((resolve) => {
            finishOld = resolve;
          })
        : Promise.resolve({
            releaseId: 'B',
            page: 0,
            size: 20,
            total: 1,
            resources: [{ resourceId: 'new', kind: 'METRIC', name: 'new metric', queryable: true }],
          }),
    ),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?releaseId=A']}>
      <SwitchRelease />
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: '切换版本' }));
  expect(await screen.findByText('new metric')).toBeTruthy();
  finishOld?.({
    releaseId: 'A',
    page: 0,
    size: 20,
    total: 1,
    resources: [{ resourceId: 'old', kind: 'METRIC', name: 'old metric', queryable: true }],
  });
  expect(screen.queryByText('old metric')).toBeNull();
});

it('shows a cleaned release without loading the current catalog', async () => {
  const api = {
    getDbtCatalog: vi
      .fn()
      .mockRejectedValue(new ApiProblem({ status: 410, title: 'Gone', detail: 'cleaned' })),
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
  expect(await screen.findByText('该发布版本已清理')).toBeTruthy();
  expect(api.getDbtCatalog).toHaveBeenCalledWith('demo', 'old', '', 0, 20, undefined);
});

it('requires a releaseId and rejects a different catalog release', async () => {
  const api = { getDbtCatalog: vi.fn().mockResolvedValue({ releaseId: 'B', resources: [] }) };
  const view = render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('缺少发布版本 releaseId')).toBeTruthy();
  expect(api.getDbtCatalog).not.toHaveBeenCalled();
  view.unmount();
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?releaseId=A']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('目录版本与请求不一致')).toBeTruthy();
  expect(screen.queryByText('releaseId：B')).toBeNull();
});
