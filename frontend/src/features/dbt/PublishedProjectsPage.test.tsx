import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { PublishedProjectsPage } from './PublishedProjectsPage';
afterEach(cleanup);
it('Agent失联时提示项目不可用，不发送缺少repository的构建请求', async () => {
  const api = {
    projects: vi.fn().mockRejectedValue(new Error('offline')),
    builds: vi.fn().mockResolvedValue({
      items: [
        {
          buildId: '11111111-1111-4111-8111-111111111111',
          repository: 'repo',
          branchName: 'feature/a',
          buildStatus: 'FAILED',
        },
      ],
      nextCursor: null,
      source: 'SERVICE_CHANGE_FEED',
      syncedAt: '2026-10-10',
    }),
  };
  render(
    <MemoryRouter>
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  expect(await screen.findByText(/Agent 项目列表不可用/)).toBeInTheDocument();
  expect(api.builds).not.toHaveBeenCalled();
});

it('构建历史按服务游标追加，不改写已有固定链接', async () => {
  const api = {
    projects: vi.fn().mockResolvedValue({
      items: [{ projectId: 'p', name: '项目', repository: 'repo/a' }],
      nextCursor: null,
    }),
    builds: vi
      .fn()
      .mockResolvedValueOnce({
        items: [
          {
            buildId: '11111111-1111-4111-8111-111111111111',
            repository: 'repo/a',
            branchName: 'a',
            buildStatus: 'SUCCEEDED',
          },
        ],
        nextCursor: 'opaque',
        source: 'SERVICE_CHANGE_FEED',
      })
      .mockResolvedValueOnce({
        items: [
          {
            buildId: '22222222-2222-4222-8222-222222222222',
            repository: 'repo/a',
            branchName: 'b',
            buildStatus: 'FAILED',
          },
        ],
        nextCursor: null,
        source: 'SERVICE_CHANGE_FEED',
      }),
  };
  render(
    <MemoryRouter>
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: '加载更多构建' }));
  await screen.findByRole('link', { name: '22222222-2222-4222-8222-222222222222' });
  expect(api.builds).toHaveBeenLastCalledWith({ repository: 'repo/a', cursor: 'opaque' });
  expect(screen.getAllByRole('link')).toHaveLength(2);
});

it('切换项目仓库后忽略旧仓库晚到的分页结果', async () => {
  let complete!: (value: unknown) => void;
  const pending = new Promise((resolve) => {
    complete = resolve;
  });
  const api = {
    projects: vi.fn().mockResolvedValue({
      items: [
        { projectId: 'a', name: '项目 A', repository: 'repo/a' },
        { projectId: 'b', name: '项目 B', repository: 'repo/b' },
      ],
      nextCursor: null,
    }),
    builds: vi.fn().mockImplementation(({ repository, cursor }) =>
      cursor
        ? pending
        : Promise.resolve({
            items: [
              {
                buildId: repository === 'repo/a' ? 'build-a' : 'build-b',
                repository,
                branchName: 'main',
                buildStatus: 'SUCCEEDED',
              },
            ],
            nextCursor: repository === 'repo/a' ? 'cursor-a' : null,
          }),
    ),
  };
  render(
    <MemoryRouter>
      <PublishedProjectsPage api={api as never} />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: '加载更多构建' }));
  await waitFor(() =>
    expect(api.builds).toHaveBeenCalledWith({ repository: 'repo/a', cursor: 'cursor-a' }),
  );
  fireEvent.change(screen.getByLabelText('构建历史仓库'), { target: { value: 'repo/b' } });
  await screen.findByRole('link', { name: 'build-b' });
  await act(async () => {
    complete({ items: [{ buildId: 'stale-a' }], nextCursor: null });
  });
  expect(screen.queryByRole('link', { name: 'stale-a' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'build-a' })).not.toBeInTheDocument();
  expect(api.builds).toHaveBeenLastCalledWith({ repository: 'repo/b' });
});
