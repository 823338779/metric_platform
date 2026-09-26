import { expect, it, vi } from 'vitest';
import { createMetricPlatformApi } from './metricPlatformApi';

it('submits a dbt query against the selected catalog release without SQL', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ queryId: 'q', releaseId: 'r', status: 'PENDING' }), {
      status: 202,
    }),
  );
  await createMetricPlatformApi(fetcher).submitDbtQuery('demo', {
    releaseId: 'r',
    metrics: ['revenue'],
    limit: 10,
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining('/semantic-projects/demo/queries'),
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ releaseId: 'r', metrics: ['revenue'], limit: 10 }),
    }),
  );
});

it('encodes multiple metrics and the fixed release in query options', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'));
  await createMetricPlatformApi(fetcher).getDbtQueryOptions('demo', 'release-1', [
    'net revenue',
    'orders',
  ]);
  expect(fetcher.mock.calls[0][0]).toContain('releaseId=release-1');
  expect(fetcher.mock.calls[0][0]).toContain('metrics=net+revenue');
  expect(fetcher.mock.calls[0][0]).toContain('metrics=orders');
});

it('loads presentation and source for the same release', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  const api = createMetricPlatformApi(fetcher);
  await api.getDbtPresentation('demo', 'metric.revenue', 'release-1');
  await api.getDbtSource('demo', 'metric.revenue', 'release-1');
  expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
    expect.stringContaining('/presentation?releaseId=release-1'),
    expect.stringContaining('/source?releaseId=release-1'),
  ]);
});

it('submits dimension values with fixed release and typed filters', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'));
  const body = {
    releaseId: 'release-1',
    metrics: ['revenue'],
    dimension: 'region',
    filters: [{ field: 'country', operator: '=', value: 'CN' }],
    limit: 20,
  };
  await createMetricPlatformApi(fetcher).submitDbtDimensionValues('demo', body);
  expect(fetcher.mock.calls[0][1]).toEqual(
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify(body),
    }),
  );
});
