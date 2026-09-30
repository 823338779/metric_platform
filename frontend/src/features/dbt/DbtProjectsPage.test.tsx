import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { DbtProjectsPage } from './DbtProjectsPage';
import type { DbtProject, DbtRelease } from '../../api/types';

const REFRESH = '手动刷新';
const ACCEPTED = '刷新请求已受理，等待后台同步和发布；当前版本仍可使用。';
const DISABLED = '手动刷新不可用，请配置 Git 仓库、项目绑定并启用 dbt 运行服务';

beforeAll(() => {
  const getComputedStyle = window.getComputedStyle;
  window.getComputedStyle = (element) => getComputedStyle(element);
  window.matchMedia = vi.fn().mockImplementation(() => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

it('submits refresh even without projects and prevents duplicate clicks while pending', async () => {
  let accept!: () => void;
  const api = {
    listDbtReleases: vi.fn().mockResolvedValue([]),
    listDbtProjects: vi.fn().mockResolvedValue([]),
    refreshDbtProjects: vi.fn().mockImplementation(
      () =>
        new Promise<{ accepted: boolean }>((resolve) => {
          accept = () => resolve({ accepted: true });
        }),
    ),
  };
  render(
    <MemoryRouter>
      <DbtProjectsPage api={api} />
    </MemoryRouter>,
  );
  const button = screen.getByRole('button', { name: REFRESH });
  fireEvent.click(button);
  expect(button.hasAttribute('disabled')).toBe(true);
  expect(api.refreshDbtProjects).toHaveBeenCalledTimes(1);
  expect(screen.queryByText(ACCEPTED)).toBeNull();
  accept();
  expect(await screen.findByText(ACCEPTED)).toBeTruthy();
  await waitFor(() => expect(button.hasAttribute('disabled')).toBe(false));
});

it('shows missing configuration without claiming refresh was accepted', async () => {
  const api = {
    listDbtReleases: vi.fn().mockResolvedValue([]),
    listDbtProjects: vi.fn().mockResolvedValue([]),
    refreshDbtProjects: vi
      .fn()
      .mockRejectedValue(new ApiProblem({ status: 503, title: DISABLED, detail: DISABLED })),
  };
  render(
    <MemoryRouter>
      <DbtProjectsPage api={api} />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: REFRESH }));
  expect(await screen.findByText(DISABLED)).toBeTruthy();
  expect(screen.queryByText(ACCEPTED)).toBeNull();
});

it('updates the published version after acceptance and stops polling when leaving', async () => {
  vi.useFakeTimers();
  const api = {
    listDbtReleases: vi.fn().mockResolvedValue([]),
    listDbtProjects: vi.fn().mockResolvedValue([]),
    refreshDbtProjects: vi.fn().mockResolvedValue({ accepted: true }),
  };
  const page = render(
    <MemoryRouter>
      <DbtProjectsPage api={api} />
    </MemoryRouter>,
  );
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: REFRESH }));
  });
  api.listDbtProjects.mockResolvedValue([{ projectId: 'sales', activeReleaseId: 'new-release' }]);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(screen.getByText('new-release')).toBeTruthy();
  page.unmount();
  const calls = api.listDbtProjects.mock.calls.length;
  await vi.advanceTimersByTimeAsync(5000);
  expect(api.listDbtProjects).toHaveBeenCalledTimes(calls);
});

const project = {
  projectId: 'sales',
  observedSha: 'new-sha',
  activeReleaseId: 'old-release',
  lastObservationStatus: 'CANDIDATE',
  lastObservedAt: '2026-09-30T04:00:00Z',
} as DbtProject;
const release = {
  releaseId: 'new-release',
  projectId: 'sales',
  targetCommitSha: 'new-sha',
  buildSourceSha: 'new-sha',
  projectDigest: 'digest',
  state: 'BUILDING',
  cleanupState: 'PENDING',
  schemaName: null,
  errorCode: null,
  createdAt: '2026-09-30T04:00:00Z',
  publishedAt: null,
} satisfies DbtRelease;

it('shows the actual failed release instead of candidate or unchanged observation', async () => {
  const api = {
    listDbtProjects: vi
      .fn()
      .mockResolvedValue([{ ...project, lastObservationStatus: 'SKIPPED_UNCHANGED' }]),
    listDbtReleases: vi
      .fn()
      .mockResolvedValue([{ ...release, state: 'FAILED', errorCode: 'RUN_FAILED' }]),
    refreshDbtProjects: vi.fn(),
  };
  render(
    <MemoryRouter>
      <DbtProjectsPage api={api} />
    </MemoryRouter>,
  );
  expect(await screen.findByText('构建或校验失败')).toBeTruthy();
  expect(screen.getByText(/运行服务未提供具体子阶段/)).toBeTruthy();
  expect(screen.getByText(/当前仍使用上一个成功发布版本/)).toBeTruthy();
});

it('does not mistake an old published result for the newly accepted refresh and shows unchanged after observation', async () => {
  vi.useFakeTimers();
  const api = {
    listDbtProjects: vi.fn().mockResolvedValue({}),
    listDbtReleases: vi.fn().mockResolvedValue([{ ...release, state: 'PUBLISHED' }]),
    refreshDbtProjects: vi.fn().mockResolvedValue({ accepted: true }),
  };
  api.listDbtProjects.mockResolvedValue([
    { ...project, activeReleaseId: release.releaseId, lastObservationStatus: 'SKIPPED_UNCHANGED' },
  ]);
  render(
    <MemoryRouter>
      <DbtProjectsPage api={api} />
    </MemoryRouter>,
  );
  await act(async () => {});
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: REFRESH }));
  });
  expect(screen.getByText(ACCEPTED)).toBeTruthy();
  api.listDbtProjects.mockResolvedValue([
    {
      ...project,
      activeReleaseId: release.releaseId,
      lastObservationStatus: 'SKIPPED_UNCHANGED',
      lastObservedAt: '2026-09-30T04:01:00Z',
    },
  ]);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(screen.queryByText(ACCEPTED)).toBeNull();
  expect(screen.getByText('本次无需重新构建')).toBeTruthy();
});

it('polls existing builds and completes publication while retaining old tables', async () => {
  vi.useFakeTimers();
  const api = {
    listDbtProjects: vi.fn().mockResolvedValue([project]),
    listDbtReleases: vi.fn().mockResolvedValue([release]),
    refreshDbtProjects: vi.fn(),
  };
  render(
    <MemoryRouter>
      <DbtProjectsPage api={api} />
    </MemoryRouter>,
  );
  await act(async () => {});
  expect(screen.getByText('正在构建与校验')).toBeTruthy();
  api.listDbtProjects.mockResolvedValue([{ ...project, activeReleaseId: release.releaseId }]);
  api.listDbtReleases.mockResolvedValue([
    { ...release, state: 'PUBLISHED' },
    { ...release, releaseId: 'old-release', state: 'PUBLISHED', cleanupState: 'PENDING' },
  ]);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(screen.getByText('发布成功')).toBeTruthy();
  expect(screen.getByText('旧物理表保留')).toBeTruthy();
  expect(screen.queryByText('清理历史构建')).toBeNull();
  api.listDbtReleases.mockResolvedValue([
    { ...release, state: 'PUBLISHED' },
    { ...release, releaseId: 'old-release', state: 'PUBLISHED', cleanupState: 'CLEANED' },
  ]);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(screen.getByText('发布成功')).toBeTruthy();
});
