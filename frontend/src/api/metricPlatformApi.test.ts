import { expect, it, vi } from 'vitest';
import type { QueryConnectivityResponse } from './types';
import { createMetricPlatformApi } from './metricPlatformApi';

it('loads the query connectivity snapshot', async () => {
  const payload: QueryConnectivityResponse = {
    catalogVersion: 'catalog-1',
    datasets: [],
    paths: [],
  };
  const fetcher = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(payload), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  );

  await expect(createMetricPlatformApi(fetcher).getQueryConnectivity()).resolves.toEqual(payload);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining('/query-connectivity'),
    expect.objectContaining({
      headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
    }),
  );
});

it('uses the shared semantic, member, and deletion-impact endpoints', async () => {
  const fetcher = vi.fn().mockImplementation(() =>
    Promise.resolve(
      new Response(JSON.stringify({ items: [], pageInfo: { hasMore: false, limit: 20 } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
  const api = createMetricPlatformApi(fetcher);

  await api.querySemanticObjects({ types: ['METRIC', 'DIMENSION'], limit: 20 });
  await api.queryDimensionMembers('dimension-1', { mode: 'AUTO', limit: 20 });
  await api.metricDeletionImpact('metric-1');
  await api.dimensionDeletionImpact('dimension-1');
  await api.relationshipDeletionImpact('relationship-1');

  expect(fetcher).toHaveBeenNthCalledWith(
    1,
    expect.stringContaining('/semantic-objects:query'),
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ types: ['METRIC', 'DIMENSION'], limit: 20 }),
    }),
  );
  expect(fetcher).toHaveBeenNthCalledWith(
    2,
    expect.stringContaining('/dimensions/dimension-1/members:query'),
    expect.objectContaining({ method: 'POST' }),
  );
  expect(fetcher).toHaveBeenNthCalledWith(
    3,
    expect.stringContaining('/metrics/metric-1/deletion-impact'),
    expect.any(Object),
  );
  expect(fetcher).toHaveBeenNthCalledWith(
    4,
    expect.stringContaining('/dimensions/dimension-1/deletion-impact'),
    expect.any(Object),
  );
  expect(fetcher).toHaveBeenNthCalledWith(
    5,
    expect.stringContaining('/dataset-relationships/relationship-1/deletion-impact'),
    expect.any(Object),
  );
});
