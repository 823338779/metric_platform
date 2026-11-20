import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { PublishedCatalogPage } from './PublishedCatalogPage';
import type { BuildView } from '../../api/buildCatalogApi';
vi.mock('./DbtCatalogPage', () => ({ DbtCatalogPage: () => <p>固定构建目录</p> }));
afterEach(cleanup);
const B1 = '11111111-1111-4111-8111-111111111111';
const B2 = '22222222-2222-4222-8222-222222222222';
const build = (id = B1) =>
  ({
    buildId: id,
    repository: 'repo/a',
    branchName: 'feature/shared',
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
    buildStatus: 'SUCCEEDED',
    catalogAvailable: true,
    commitSha: 'sha1',
    requestedCommitSha: null,
    toolchainVersion: 'test',
    phase: 'COMPLETE',
    createdAt: '',
    updatedAt: '',
    finishedAt: null,
    error: null,
    initialDeployment: null,
  }) as BuildView;
function fixture() {
  return {
    build: vi.fn(async (id: string) => build(id)),
    current: vi
      .fn()
      .mockResolvedValue({ activeBuildId: B2, activeCommitSha: 'sha2', sourceState: 'AHEAD' }),
    builds: vi.fn().mockResolvedValue({
      items: [build(), build(B2)],
      nextCursor: null,
      source: 'SERVICE_CHANGE_FEED',
      syncedAt: '2026-10-10T00:00:00Z',
    }),
    deployments: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    branches: vi.fn().mockResolvedValue({
      items: [{ branchName: 'feature/shared', headSha: 'sha2' }],
      cached: false,
      source: 'AGENT',
      observedAt: 'now',
    }),
    projects: vi.fn().mockResolvedValue({
      items: [{ projectId: 'p1', name: '项目', repository: 'repo/a', developmentConfigured: true }],
      nextCursor: null,
    }),
  };
}
function mount(api: unknown, path = `/dbt/builds/${B1}/catalog`) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/dbt/builds/:buildId/catalog"
          element={<PublishedCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}
it('deploymentChangeDoesNotReplacePageBuild', async () => {
  const api = fixture();
  mount(api);
  expect(await screen.findByText('固定构建目录')).toBeInTheDocument();
  expect(await screen.findByText(/有效部署已更新/)).toBeInTheDocument();
  expect(api.build).toHaveBeenCalledWith(B1);
  expect(api.build).not.toHaveBeenCalledWith(B2);
  fireEvent.click(screen.getByRole('button', { name: '切换到当前部署' }));
  await waitFor(() => expect(api.build).toHaveBeenCalledWith(B2));
});
it('undeployedBranchDoesNotUseMainCatalog', async () => {
  const api = fixture();
  api.build.mockResolvedValue({ ...build(), catalogAvailable: false });
  api.current.mockResolvedValue({ activeBuildId: null, sourceState: 'UNDEPLOYED' });
  mount(api, `/dbt/builds/${B1}/catalog?buildId=${B2}&branchName=main`);
  expect(await screen.findByText('当前分支尚未部署')).toBeInTheDocument();
  expect(screen.queryByText('固定构建目录')).not.toBeInTheDocument();
  expect(api.build.mock.calls.map(([id]) => id)).toEqual([B1]);
  expect(screen.queryByRole('button', { name: '切换到当前部署' })).not.toBeInTheDocument();
  expect(api.current).toHaveBeenCalledWith({
    repository: 'repo/a',
    branchName: 'feature/shared',
    environmentId: '12345678-1234-4234-8234-000000000001',
  });
});
it('historicalCatalogVisibleWithoutQueryAvailabilityGate', async () => {
  const api = fixture();
  api.build.mockResolvedValue({
    ...build(),
  });
  mount(api);
  expect(await screen.findByText('固定构建目录')).toBeInTheDocument();
  expect(screen.queryByText(/查询不可用/)).not.toBeInTheDocument();
});

it('构建与部署历史各自保留游标和完整分支作用域', async () => {
  const api = fixture();
  api.builds
    .mockResolvedValueOnce({ items: [build()], nextCursor: 'build-page-2' })
    .mockResolvedValueOnce({ items: [build(B2)], nextCursor: null });
  api.deployments
    .mockResolvedValueOnce({
      items: [{ generation: 1, buildId: B1, deploymentStatus: 'DEPLOYED' }],
      nextCursor: 'deployment-page-2',
    })
    .mockResolvedValueOnce({
      items: [
        { generation: 2, buildId: B2, deploymentStatus: 'FAILED', reason: 'older diagnostic' },
      ],
      nextCursor: null,
    });
  mount(api);
  fireEvent.click(await screen.findByRole('button', { name: '加载更多构建历史' }));
  fireEvent.click(await screen.findByRole('button', { name: '加载更多部署历史' }));
  await screen.findByText(/older diagnostic/);
  expect(api.builds).toHaveBeenLastCalledWith({
    repository: 'repo/a',
    branchName: 'feature/shared',
    environmentId: '12345678-1234-4234-8234-000000000001',
    cursor: 'build-page-2',
  });
  expect(api.deployments).toHaveBeenLastCalledWith(
    {
      repository: 'repo/a',
      branchName: 'feature/shared',
      environmentId: '12345678-1234-4234-8234-000000000001',
    },
    'deployment-page-2',
  );
  expect(screen.queryByRole('button', { name: '加载更多构建历史' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '加载更多部署历史' })).not.toBeInTheDocument();
});

it('切换仓库后的旧历史分页晚到不能混入新作用域', async () => {
  const api = fixture();
  let finish!: (value: unknown) => void;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  const oldId = '66666666-6666-4666-8666-666666666666';
  api.build.mockImplementation(async (id) => ({
    ...build(id),
    repository: id === B2 ? 'repo/b' : 'repo/a',
  }));
  api.builds
    .mockResolvedValueOnce({ items: [build()], nextCursor: 'old-cursor' })
    .mockImplementationOnce(() => pending as never)
    .mockResolvedValue({ items: [{ ...build(B2), repository: 'repo/b' }], nextCursor: null });
  mount(api);
  fireEvent.click(await screen.findByRole('button', { name: '加载更多构建历史' }));
  fireEvent.click(await screen.findByRole('button', { name: '切换到当前部署' }));
  await waitFor(() =>
    expect(api.builds).toHaveBeenLastCalledWith({
      repository: 'repo/b',
      branchName: 'feature/shared',
      environmentId: '12345678-1234-4234-8234-000000000001',
    }),
  );
  await act(async () =>
    finish({
      items: [{ ...build(oldId), commitSha: 'old-page-must-not-appear' }],
      nextCursor: null,
    }),
  );
  expect(screen.queryByText(/old-page-must-not-appear/)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '加载更多构建历史' })).not.toBeInTheDocument();
});
