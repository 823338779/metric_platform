import { describe, expect, it, vi } from 'vitest';
import { ApiProblem, createHttpClient } from './httpClient';
import { createMetricPlatformApi } from './metricPlatformApi';

describe('typed API client', () => {
  it('submits a versioned typed query without SQL', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, json: async () => ({ valid: true }) });
    const api = createMetricPlatformApi(fetcher);
    await api.submitDbtQuery('sales', { releaseId: 'release-1', metrics: ['revenue'] });
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining('/semantic-projects/sales/queries'),
      expect.objectContaining({
        body: JSON.stringify({ releaseId: 'release-1', metrics: ['revenue'] }),
      }),
    );
  });
  it('parses Problem Details', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ title: 'Conflict', status: 409, detail: 'stale version' }),
    });
    await expect(
      createHttpClient(fetcher)('/semantic-projects/sales/catalog'),
    ).rejects.toMatchObject({
      problem: expect.objectContaining({ status: 409, detail: 'stale version' }),
    });
  });
});
