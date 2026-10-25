import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => vi.unstubAllGlobals());
it('执行日志携带不透明游标、固定页大小和取消信号，不读取旧 logs 路由', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(
      new Response(JSON.stringify({ items: [], nextCursor: 'opaque+/=', hasMore: false })),
    );
  vi.stubGlobal('fetch', fetcher);
  vi.resetModules();
  const { environmentApi } = await import('./environmentApi');
  const controller = new AbortController();
  const page = await environmentApi.logs('build/a', 'opaque+/=', controller.signal);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(fetcher.mock.calls[0][0]).toBe(
    '/api/dbt/builds/build%2Fa/execution-logs?limit=100&cursor=opaque%2B%2F%3D',
  );
  expect(fetcher.mock.calls[0][1].signal).toBe(controller.signal);
  expect(page).toEqual({ items: [], nextCursor: 'opaque+/=', hasMore: false });
});
