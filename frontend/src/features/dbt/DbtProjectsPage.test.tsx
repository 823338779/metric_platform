import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { DbtProjectsPage } from './DbtProjectsPage';

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
