import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { PublishedProjectsPage } from './PublishedProjectsPage';

afterEach(cleanup);
const BUILD = '11111111-1111-4111-8111-111111111111';
const KEY = { repository: 'repo/a', branchName: 'main', environment: 'PRODUCTION' };
const PATH = '/dbt/projects?repository=repo%2Fa&branchName=main&environment=PRODUCTION';

it('直接读取部署历史，每个历史版本都有固定目录和查询入口', async () => {
  const api = {
    projects: vi.fn().mockRejectedValue(new Error('Agent offline')),
    deployments: vi.fn().mockResolvedValue({
      items: [
        {
          ...KEY,
          generation: 1,
          buildId: BUILD,
          deploymentStatus: 'DEPLOYED',
          deployedAt: '2026-10-12T04:26:27Z',
        },
      ],
      nextCursor: null,
    }),
    current: vi.fn().mockResolvedValue({ activeBuildId: BUILD }),
  };
  render(
    <MemoryRouter initialEntries={[PATH]}>
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  expect(await screen.findByRole('link', { name: '查询此版本' })).toHaveAttribute(
    'href',
    `/dbt/builds/${BUILD}/query`,
  );
  expect(screen.getByRole('link', { name: '查看指标目录' })).toHaveAttribute(
    'href',
    `/dbt/builds/${BUILD}/catalog`,
  );
  expect(screen.getByText('当前生效')).toBeInTheDocument();
  expect(api.deployments).toHaveBeenCalledWith(KEY);
  expect(api.projects).not.toHaveBeenCalled();
});

it('没有仓库时提示填写，不发送无作用域的请求', async () => {
  const api = { projects: vi.fn(), deployments: vi.fn(), current: vi.fn() };
  render(
    <MemoryRouter>
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  expect(screen.getByLabelText('Git 仓库地址')).toBeInTheDocument();
  expect(api.projects).not.toHaveBeenCalled();
  expect(api.deployments).not.toHaveBeenCalled();
});

it('部署失败不会显示查询入口，当前指针失联不阻止历史浏览', async () => {
  const api = {
    deployments: vi.fn().mockResolvedValue({
      items: [
        {
          ...KEY,
          generation: 2,
          buildId: BUILD,
          deploymentStatus: 'FAILED',
          reason: 'BUILD_FAILED',
        },
      ],
      nextCursor: null,
    }),
    current: vi.fn().mockRejectedValue(new Error('offline')),
  };
  render(
    <MemoryRouter initialEntries={[PATH]}>
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  expect(await screen.findByText('BUILD_FAILED')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '查询此版本' })).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看指标目录' })).toBeInTheDocument();
});

it('筛选条件提交后按完整部署键读取', async () => {
  const api = {
    deployments: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    current: vi.fn().mockResolvedValue({ activeBuildId: null }),
  };
  render(
    <MemoryRouter>
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText('Git 仓库地址'), { target: { value: 'repo/b' } });
  fireEvent.change(screen.getByLabelText('分支'), { target: { value: 'feature/a' } });
  fireEvent.change(screen.getByLabelText('环境'), { target: { value: 'PREVIEW' } });
  fireEvent.click(screen.getByRole('button', { name: '查看部署版本' }));
  await waitFor(() =>
    expect(api.deployments).toHaveBeenCalledWith({
      repository: 'repo/b',
      branchName: 'feature/a',
      environment: 'PREVIEW',
    }),
  );
});
