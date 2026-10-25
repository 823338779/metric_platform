import { expect, it, vi } from 'vitest';
import { createMetricPlatformApi } from './metricPlatformApi';
const B = '11111111-1111-4111-8111-111111111111';
it('展示适配器只调用固定build资源接口，源码与native详情保留', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify({
        buildId: B,
        resourceId: 'x',
        resource: { resource_type: 'metric', unknown: 42 },
        source: { path: 'models/x.yml', content: 'unknown: true' },
      }),
    ),
  );
  const api = createMetricPlatformApi(fetcher);
  expect((await api.getDbtPresentation('agent-project', 'x', B)).nativeDetails).toEqual({
    resource_type: 'metric',
    unknown: 42,
  });
  expect((await api.getDbtSource('agent-project', 'x', B)).content).toBe('unknown: true');
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual([`/api/dbt/builds/${B}/resources/x`]);
});
it('原生目录在本地分页筛选，同一固定构建只读取一次 Manifest', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify({
        metrics: {
          a: { resource_type: 'metric', name: 'alpha' },
          b: { resource_type: 'metric', name: 'beta' },
        },
      }),
    ),
  );
  const api = createMetricPlatformApi(fetcher);
  const first = await api.getDbtCatalog('p', B, '', 0, 1);
  const second = await api.getDbtCatalog('p', B, '', 1, 1);
  expect(first.resources[0].name).toBe('alpha');
  expect(second.resources[0].name).toBe('beta');
  expect(second.total).toBe(2);
  expect((await api.getDbtCatalog('p', B, 'beta', 0, 20)).resources).toHaveLength(1);
  expect(fetcher).toHaveBeenCalledTimes(1);
});
