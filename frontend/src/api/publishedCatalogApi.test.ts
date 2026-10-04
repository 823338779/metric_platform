import { describe, expect, it, vi } from 'vitest';
import { createPublishedCatalogApi } from './publishedCatalogApi';

describe('固定分支 API', () => {
  it('目录、选项与查询均保留原分支和版本', async () => {
    const fetcher = vi
      .fn()
      .mockImplementation(
        async () => new Response(JSON.stringify({ branchId: 'branch-a', releaseId: 'release-a' })),
      );
    const api = createPublishedCatalogApi(fetcher).inBranch({
      projectId: 'p',
      branchId: 'branch-a',
      releaseId: 'release-a',
    });
    await api.catalog('p', 'release-a');
    await api.options('p', 'release-a', ['same-name']);
    const body = {
      releaseId: 'release-a',
      idempotencyKey: 'fixed-key',
      mode: 'QUERY' as const,
      metricResourceIds: ['same-name'],
    };
    await api.submit('p', body);
    await api.submit('p', body);
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      '/api/v2/semantic-projects/p/branches/branch-a/releases/release-a/catalog?q=&page=1&size=50',
      '/api/v2/semantic-projects/p/branches/branch-a/query-options',
      '/api/v2/semantic-projects/p/branches/branch-a/queries',
      '/api/v2/semantic-projects/p/branches/branch-a/queries',
    ]);
    expect(fetcher.mock.calls[2][1].body).toBe(fetcher.mock.calls[3][1].body);
  });

  it('错分支响应拒绝展示，410 不回退生产', async () => {
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        async () => new Response(JSON.stringify({ branchId: 'b', releaseId: 'r' })),
      )
      .mockImplementationOnce(
        async () =>
          new Response(JSON.stringify({ status: 410, detail: '版本不可用' }), { status: 410 }),
      );
    const api = createPublishedCatalogApi(fetcher).inBranch({
      projectId: 'p',
      branchId: 'a',
      releaseId: 'r',
    });
    await expect(api.catalog('p', 'r')).rejects.toThrow('归属');
    await expect(api.catalog('p', 'r')).rejects.toThrow('版本不可用');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
