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
