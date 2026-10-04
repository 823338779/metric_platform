import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate, useLocation } from 'react-router-dom';
import { PublishedCatalogPage } from './PublishedCatalogPage';
import type { BranchView } from '../../api/publishedCatalogApi';
vi.mock('./DbtCatalogPage', () => ({ DbtCatalogPage: () => <p>目录已加载</p> }));
afterEach(cleanup);
const branch = (id: string): BranchView => ({
  projectId: 'p',
  branchId: id,
  gitRef: 'refs/heads/' + id,
  mode: id === 'main' ? 'PRODUCTION' : 'PREVIEW',
  status: 'ACTIVE',
  baseCommitSha: 'base',
  baseReleaseId: null,
  observedHeadSha: id + '-sha',
  activeReleaseId: null,
  latestReleaseId: null,
  version: 1,
  mergeRequestUrl: null,
});
function Navigation() {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate('/dbt/projects/p/catalog?branchId=b')}>切到 B</button>
      <output>{useLocation().search}</output>
    </>
  );
}
const mount = (api: unknown, query: string) =>
  render(
    <MemoryRouter initialEntries={['/dbt/projects/p/catalog' + query]}>
      <Navigation />
      <Routes>
        <Route
          path="/dbt/projects/:projectId/catalog"
          element={<PublishedCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
function fixture() {
  return {
    diff: vi.fn().mockResolvedValue({
      branchId: 'a',
      releaseId: 'r',
      files: [],
      resources: [],
      productionAdvanced: false,
      resourceBaselineAvailable: true,
      truncated: false,
    }),
    branches: vi.fn().mockResolvedValue([branch('main'), branch('a'), branch('b')]),
    branch: vi.fn(async (_project: string, id: string) => branch(id)),
    inBranch: vi.fn().mockReturnValue({ releases: vi.fn().mockResolvedValue([]) }),
    publication: vi.fn().mockResolvedValue({ activePublication: null }),
  };
}

it('旧链接明确解析生产分支，不跟随上次开发分支', async () => {
  const api = fixture();
  mount(api, '');
  expect(await screen.findByText('线上', { selector: '.ant-tag' })).toBeInTheDocument();
  expect(screen.getByRole('status').textContent).toContain('branchId=main');
  expect(api.branch).toHaveBeenCalledWith('p', 'main');
});
it('切换 B 后忽略 A 的延迟分支响应', async () => {
  const api = fixture();
  let finish!: (value: BranchView) => void;
  api.branch.mockImplementation(async (_project: string, id: string) =>
    id === 'a'
      ? new Promise((resolve) => {
          finish = resolve;
        })
      : branch(id),
  );
  mount(api, '?branchId=a');
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: '切到 B' }));
  await screen.findByText('b-sha');
  await act(async () => finish(branch('a')));
  expect(screen.queryByText('a-sha')).not.toBeInTheDocument();
});
it('删除的旧分支链接不打开同名新分支', async () => {
  const api = fixture();
  api.branch.mockResolvedValue({ ...branch('old'), status: 'DELETED' });
  mount(api, '?branchId=old&releaseId=old-release');
  expect(await screen.findByText(/原分支不可用/)).toBeInTheDocument();
  expect(screen.queryByText('目录已加载')).not.toBeInTheDocument();
});

it('列表没有测试摘要时读取固定最新构建详情', async () => {
  const api = fixture();
  api.branch.mockResolvedValue({ ...branch('a'), activeReleaseId: 'r', latestReleaseId: 'failed' });
  const releases = [
    { releaseId: 'r', state: 'PUBLISHED', sourceSha: 'good' },
    { releaseId: 'failed', state: 'FAILED', sourceSha: 'bad' },
  ];
  const detail = vi.fn(async (_project: string, id: string) => ({
    ...releases.find((item) => item.releaseId === id),
    validationSummary: { checks: [{ name: 'actual_check', status: 'FAILED' }] },
  }));
  api.inBranch.mockReturnValue({
    releases: vi.fn().mockResolvedValue(releases),
    release: detail,
  } as never);
  mount(api, '?branchId=a&releaseId=r');
  await screen.findByText('目录已加载');
  expect(detail).toHaveBeenCalledWith('p', 'failed');
  expect(screen.getAllByText('actual_check：FAILED')).toHaveLength(2);
});
