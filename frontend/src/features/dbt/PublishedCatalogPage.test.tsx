import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { PublishedCatalogPage } from './PublishedCatalogPage';
vi.mock('./DbtCatalogPage', () => ({ DbtCatalogPage: () => <p>目录已加载</p> }));
afterEach(cleanup);
it('版本侧栏可开关，完整提交信息按需展开且刷新不切换构建', async () => {
  const buildId = '11111111-1111-4111-8111-111111111111';
  const api = {
    build: vi.fn().mockResolvedValue({
      buildId,
      repository: 'https://example.com/team/metrics.git',
      branchName: 'main',
      commitSha: 'abcdef0123456789',
      catalogAvailable: true,
      execution: {
        environmentId: 'env',
        configVersion: 3,
        catalog: 'warehouse',
        schema: 'analytics',
      },
    }),
    current: vi.fn().mockResolvedValue({ activeBuildId: buildId }),
    builds: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    deployments: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
  };
  render(
    <MemoryRouter initialEntries={[`/dbt/builds/${buildId}/catalog`]}>
      <Routes>
        <Route
          path="/dbt/builds/:buildId/catalog"
          element={<PublishedCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  await screen.findByText('目录已加载');
  const toggle = screen.getByRole('button', { name: '版本上下文' });
  if (toggle.getAttribute('aria-expanded') === 'false') fireEvent.click(toggle);
  const context = screen.getByRole('complementary', { name: '版本上下文' });
  expect(within(context).getByText('main')).toBeVisible();
  const details = within(context).getByText('版本详情').closest('details')!;
  fireEvent.click(within(details).getByText('版本详情'));
  expect(within(context).getByText('abcdef0123456789')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '收起版本上下文' }));
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(screen.getByRole('button', { name: '刷新构建状态' }));
  await waitFor(() => expect(api.build).toHaveBeenCalledTimes(2));
  expect(api.build).toHaveBeenLastCalledWith(buildId);
});
it('固定构建链接读取历史，与Agent分支登记无关', async () => {
  const B = '11111111-1111-4111-8111-111111111111';
  const api = {
    build: vi.fn().mockResolvedValue({
      buildId: B,
      repository: 'repo',
      branchName: 'feature/a',
      execution: {
        environmentId: '12345678-1234-4234-8234-000000000001',
        configVersion: 1,
        connection: {
          type: 'starrocks',
          instanceId: 'warehouse',
          host: 'localhost',
          port: 9030,
          path: null,
        },
        credentialRef: 'warehouse-test',
        catalog: 'default_catalog',
        schema: 'analytics',
        threads: 2,
        businessTimezone: 'Asia/Shanghai',
      },
      catalogAvailable: true,
    }),
    current: vi.fn().mockRejectedValue(new Error('offline')),
    builds: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    deployments: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    branches: vi.fn(),
  };
  render(
    <MemoryRouter initialEntries={[`/dbt/builds/${B}/catalog`]}>
      <Routes>
        <Route
          path="/dbt/builds/:buildId/catalog"
          element={<PublishedCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('目录已加载')).toBeInTheDocument();
  await waitFor(() => expect(api.build).toHaveBeenCalledWith(B));
  expect(api.branches).not.toHaveBeenCalled();
});
it('直接打开已清理构建保留版本信息并提示产物不可用', async () => {
  const buildId = 'retired';
  const api = {
    build: vi.fn().mockResolvedValue({
      buildId,
      branchName: 'main',
      repository: 'repo',
      execution: { environmentId: 'env' },
      catalogAvailable: false,
    }),
    current: vi.fn().mockResolvedValue({ activeBuildId: 'current' }),
    builds: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    deployments: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
  };
  render(
    <MemoryRouter initialEntries={[`/dbt/builds/${buildId}/catalog`]}>
      <Routes>
        <Route
          path="/dbt/builds/:buildId/catalog"
          element={<PublishedCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(
    await screen.findByText('此构建的产物不可用。请在执行环境中选择当前部署版本。'),
  ).toBeVisible();
  expect(screen.queryByText('目录已加载')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '切换到当前部署' })).toBeVisible();
});
