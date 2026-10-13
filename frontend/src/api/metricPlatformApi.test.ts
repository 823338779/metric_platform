import { expect, it, vi } from 'vitest';
import { createMetricPlatformApi } from './metricPlatformApi';
const B = '11111111-1111-4111-8111-111111111111';
it('展示适配器只调用固定build资源接口，源码与native详情保留', async () => {
  const fetcher = vi.fn().mockImplementation(
    async (url: string) =>
      new Response(
        JSON.stringify(
          url.endsWith('/source')
            ? { buildId: B, resourceId: 'x', path: 'models/x.yml', content: 'unknown: true' }
            : {
                buildId: B,
                resourceId: 'x',
                kind: 'METRIC',
                capabilities: [],
                attributes: {},
                nativeDetails: { unknown: 42 },
              },
        ),
      ),
  );
  const api = createMetricPlatformApi(fetcher);
  expect((await api.getDbtPresentation('agent-project', 'x', B)).nativeDetails).toEqual({
    unknown: 42,
  });
  expect((await api.getDbtSource('agent-project', 'x', B)).content).toBe('unknown: true');
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
    `/api/dbt/builds/${B}/resources/x`,
    `/api/dbt/builds/${B}/resources/x/source`,
  ]);
});
it('目录分页传递服务不透明cursor', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ buildId: B, items: [], nextCursor: 'opaque' })),
    )
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ buildId: B, items: [], nextCursor: null })),
    );
  const api = createMetricPlatformApi(fetcher);
  await api.getDbtCatalog('p', B, '', 0, 20);
  await api.getDbtCatalog('p', B, '', 1, 20);
  expect(fetcher.mock.calls[1][0]).toContain('cursor=opaque');
});
