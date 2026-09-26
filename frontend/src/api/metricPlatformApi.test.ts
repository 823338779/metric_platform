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
