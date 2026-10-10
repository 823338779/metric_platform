import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { PublishedProjectsPage } from './PublishedProjectsPage';
afterEach(cleanup);
it('Agent失联仍可浏览带来源和同步时间的构建历史', async () => {
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
  expect(screen.getByRole('link')).toHaveAttribute(
    'href',
    '/dbt/builds/11111111-1111-4111-8111-111111111111/catalog',
  );
  expect(screen.getByText(/SERVICE_CHANGE_FEED/)).toBeInTheDocument();
});

it('构建历史按服务游标追加，不改写已有固定链接', async () => {
  const api = {
    projects: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
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
  expect(api.builds).toHaveBeenLastCalledWith({ cursor: 'opaque' });
  expect(screen.getAllByRole('link')).toHaveLength(2);
});
