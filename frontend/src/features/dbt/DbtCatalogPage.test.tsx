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

function BackToPreviousFilter() {
  const navigate = useNavigate();
  return <button onClick={() => navigate(-1)}>返回筛选</button>;
}

it('默认展示全部指标，切换类型和历史导航均自动展示所有页', async () => {
  const client = {
    getDbtCatalog: vi
      .fn()
      .mockImplementation(
        (_: string, __: string, ___: string, page: number, ____: number, kind?: string) =>
          Promise.resolve({
            buildId: 'A',
            page,
            size: 20,
            total: 21,
            resources: Array.from({ length: page === 0 ? 20 : 1 }, (_, index) => ({
              resourceId: `${kind ?? 'all'}-${page * 20 + index}`,
              kind,
              name: `${kind ?? 'all'}-${page * 20 + index}`,
              queryable: false,
            })),
          }),
      ),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=A']}>
      <BackToPreviousFilter />
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={client as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('METRIC-20')).toBeTruthy();
  expect(screen.queryByRole('tab', { name: '全部资源' })).toBeNull();
  expect(screen.queryByRole('button', { name: '加载更多资源' })).toBeNull();
  fireEvent.click(screen.getByRole('tab', { name: '维度' }));
  expect(await screen.findByText('DIMENSION-20')).toBeTruthy();
  expect(screen.queryByText('METRIC-0')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '返回筛选' }));
  expect(await screen.findByText('METRIC-20')).toBeTruthy();
  expect(screen.queryByText('DIMENSION-0')).toBeNull();
});

it('collapses resource folders and locates a deep-linked resource inside the tree', async () => {
  const client = {
    getDbtCatalog: vi.fn().mockResolvedValue({
      buildId: 'A',
      page: 0,
      size: 20,
      total: 1,
      resources: [
        {
          resourceId: 'metric.shop.units_sold',
          kind: 'METRIC',
          name: 'units_sold',
          sourcePath: 'models/metrics.yml',
          queryable: true,
        },
      ],
    }),
  };
  render(
    <MemoryRouter
      initialEntries={['/dbt/projects/demo/catalog?buildId=A&resourceId=metric.shop.units_sold']}
    >
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={client as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  await screen.findByText('units_sold');
  fireEvent.click(screen.getByRole('button', { name: '全部折叠' }));
  expect(screen.queryByText('units_sold')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '定位当前资源' }));
  expect(await screen.findByText('units_sold')).toBeTruthy();
  expect(screen.getByRole('treeitem', { name: /units_sold/ }).getAttribute('aria-selected')).toBe(
    'true',
  );
});

it('打开跨类型资源链接后定位到所属栏目', async () => {
  const table = { resourceId: 'model.orders', kind: 'TABLE', name: 'orders' };
  const metric = { resourceId: 'metric.sales', kind: 'METRIC', name: 'sales' };
  const api = {
    getDbtCatalog: async (
      _: string,
      __: string,
      ___: string,
      page: number,
      size: number,
      kind: string,
    ) => ({ buildId: 'A', page, size, total: 1, resources: [kind === 'TABLE' ? table : metric] }),
    getDbtResource: async () => table,
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=A&resourceId=model.orders']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(
    (await screen.findByRole('treeitem', { name: /orders/ })).getAttribute('aria-selected'),
  ).toBe('true');
  expect(screen.getByRole('tab', { name: '数据表' }).getAttribute('aria-selected')).toBe('true');
  expect(screen.queryByText('sales')).toBeNull();
});

function SwitchRelease() {
  const navigate = useNavigate();
  return <button onClick={() => navigate('/dbt/projects/demo/catalog?buildId=B')}>切换版本</button>;
}

it('groups resources and searches only within the pinned release', async () => {
  const api = {
    getDbtCatalog: vi.fn().mockResolvedValue({
      buildId: 'A',
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
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=A']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('revenue')).toBeTruthy();
  expect(screen.getByRole('tab', { name: '指标' })).toBeTruthy();
  expect(screen.getByRole('tab', { name: '数据表' })).toBeTruthy();
  expect(screen.getByRole('link', { name: '探索数据' }).getAttribute('href')).toBe(
    '/dbt/builds/A/query?buildId=A&metric=revenue',
  );
  fireEvent.change(screen.getByPlaceholderText('搜索资源'), { target: { value: 'orders' } });
  fireEvent.click(screen.getByRole('button', { name: 'search' }));
  await waitFor(() =>
    expect(api.getDbtCatalog).toHaveBeenLastCalledWith('demo', 'A', 'orders', 0, 100, 'METRIC'),
  );
});

it('includes unknown resource types from later pages in the other column', async () => {
  const api = {
    getDbtCatalog: vi.fn().mockImplementation((_: string, __: string, ___: string, page: number) =>
      Promise.resolve({
        buildId: 'A',
        page,
        size: 100,
        total: 101,
        resources:
          page === 0
            ? Array.from({ length: 100 }, (_, index) => ({
                resourceId: String(index),
                kind: 'TABLE',
                name: `table_${index}`,
              }))
            : [{ resourceId: 'custom', kind: 'CUSTOM', name: 'custom resource' }],
      }),
    ),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=A&kind=OTHER']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('custom resource')).toBeTruthy();
  expect(screen.queryByText('table_0')).toBeNull();
  expect(api.getDbtCatalog).toHaveBeenLastCalledWith('demo', 'A', '', 1, 100, undefined);
});

it('asks the server to filter resource kinds before pagination', async () => {
  const api = {
    getDbtCatalog: vi
      .fn()
      .mockImplementation(
        (_: string, __: string, ___: string, ____: number, _____: number, kind?: string) =>
          Promise.resolve({
            buildId: 'A',
            page: 0,
            size: 20,
            total: 1,
            resources:
              kind === 'METRIC'
                ? [{ resourceId: 'm', kind: 'METRIC', name: 'later metric', queryable: true }]
                : [{ resourceId: 't', kind: 'TABLE', name: 'first table', queryable: true }],
          }),
      ),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=A&kind=TABLE']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('first table')).toBeTruthy();
  fireEvent.click(screen.getByRole('tab', { name: '指标' }));
  expect(await screen.findByText('later metric')).toBeTruthy();
  expect(api.getDbtCatalog).toHaveBeenLastCalledWith('demo', 'A', '', 0, 100, 'METRIC');
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
            buildId: 'B',
            page: 0,
            size: 20,
            total: 1,
            resources: [{ resourceId: 'new', kind: 'METRIC', name: 'new metric', queryable: true }],
          }),
    ),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=A']}>
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
    buildId: 'A',
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
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=old']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('该发布版本仅保留发布记录')).toBeTruthy();
  expect(api.getDbtCatalog).toHaveBeenCalledWith('demo', 'old', '', 0, 100, 'METRIC');
});

it('历史构建仍读取目录，不因当前部署变化拒绝历史资源', async () => {
  const client = {
    getDbtCatalog: vi
      .fn()
      .mockResolvedValue({ buildId: 'old', page: 0, size: 20, total: 0, resources: [] }),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=old']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={client as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('该类型暂无资源')).toBeTruthy();
  expect(client.getDbtCatalog).toHaveBeenCalledWith('demo', 'old', '', 0, 100, 'METRIC');
});
it('requires a buildId and rejects a different catalog release', async () => {
  const api = { getDbtCatalog: vi.fn().mockResolvedValue({ buildId: 'B', resources: [] }) };
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
  expect(await screen.findByText('缺少发布版本 buildId')).toBeTruthy();
  expect(api.getDbtCatalog).not.toHaveBeenCalled();
  view.unmount();
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/catalog?buildId=A']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<DbtCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('目录版本与请求不一致')).toBeTruthy();
  expect(screen.queryByText('buildId：B')).toBeNull();
});
