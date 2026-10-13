import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { PublishedProjectsPage } from './PublishedProjectsPage';
afterEach(cleanup);
it('dbt-service失联时提示部署历史错误', async () => {
  const api = {
    deployments: vi.fn().mockRejectedValue(new Error('offline')),
    current: vi.fn().mockResolvedValue({ activeBuildId: null }),
  };
  render(
    <MemoryRouter
      initialEntries={[
        '/dbt/projects?repository=repo%2Fa&environmentId=12345678-1234-4234-8234-000000000001',
      ]}
    >
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  expect(await screen.findByText('部署历史读取失败，请重试。')).toBeInTheDocument();
});

it('部署历史按服务游标追加，不改写已有固定链接', async () => {
  const api = {
    current: vi.fn().mockResolvedValue({ activeBuildId: null }),
    deployments: vi
      .fn()
      .mockResolvedValueOnce({
        items: [
          {
            generation: 1,
            buildId: '11111111-1111-4111-8111-111111111111',
            repository: 'repo/a',
            branchName: 'a',
            deploymentStatus: 'DEPLOYED',
          },
        ],
        nextCursor: 'opaque',
        source: 'SERVICE_CHANGE_FEED',
      })
      .mockResolvedValueOnce({
        items: [
          {
            generation: 2,
            buildId: '22222222-2222-4222-8222-222222222222',
            repository: 'repo/a',
            branchName: 'b',
            deploymentStatus: 'FAILED',
          },
        ],
        nextCursor: null,
        source: 'SERVICE_CHANGE_FEED',
      }),
  };
  render(
    <MemoryRouter
      initialEntries={[
        '/dbt/projects?repository=repo%2Fa&branchName=main&environmentId=12345678-1234-4234-8234-000000000001',
      ]}
    >
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: '加载更多部署版本' }));
  await waitFor(() =>
    expect(screen.getAllByRole('link', { name: '查看指标目录' })).toHaveLength(2),
  );
  expect(api.deployments).toHaveBeenLastCalledWith(
    {
      repository: 'repo/a',
      branchName: 'main',
      environmentId: '12345678-1234-4234-8234-000000000001',
    },
    'opaque',
  );
  expect(screen.getAllByRole('link')).toHaveLength(3);
});

it('切换仓库后忽略旧仓库晚到的分页结果', async () => {
  let complete!: (value: unknown) => void;
  const pending = new Promise((resolve) => {
    complete = resolve;
  });
  const api = {
    current: vi.fn().mockResolvedValue({ activeBuildId: null }),
    deployments: vi.fn().mockImplementation(({ repository }, cursor) =>
      cursor
        ? pending
        : Promise.resolve({
            items: [
              {
                generation: 1,
                buildId: repository === 'repo/a' ? 'build-a' : 'build-b',
                repository,
                branchName: 'main',
                deploymentStatus: 'DEPLOYED',
              },
            ],
            nextCursor: repository === 'repo/a' ? 'cursor-a' : null,
          }),
    ),
  };
  render(
    <MemoryRouter
      initialEntries={[
        '/dbt/projects?repository=repo%2Fa&branchName=main&environmentId=12345678-1234-4234-8234-000000000001',
      ]}
    >
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: '加载更多部署版本' }));
  await waitFor(() =>
    expect(api.deployments).toHaveBeenCalledWith(
      {
        repository: 'repo/a',
        branchName: 'main',
        environmentId: '12345678-1234-4234-8234-000000000001',
      },
      'cursor-a',
    ),
  );
  fireEvent.change(screen.getByLabelText('Git 仓库地址'), { target: { value: 'repo/b' } });
  fireEvent.click(screen.getByRole('button', { name: '查看部署版本' }));
  await screen.findByText('build-b');
  await act(async () => {
    complete({ items: [{ buildId: 'stale-a' }], nextCursor: null });
  });
  expect(screen.queryByText('stale-a')).not.toBeInTheDocument();
  expect(screen.queryByText('build-a')).not.toBeInTheDocument();
  expect(api.deployments).toHaveBeenLastCalledWith({
    repository: 'repo/b',
    branchName: 'main',
    environmentId: '12345678-1234-4234-8234-000000000001',
  });
});
