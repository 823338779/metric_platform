import { describe, expect, it, vi } from 'vitest';
import { ApiProblem, createHttpClient } from './httpClient';
import { createMetricPlatformApi } from './metricPlatformApi';

describe('typed API client', () => {
  it('sends the breaking query shape without datasetId', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, json: async () => ({ valid: true }) });
    const api = createMetricPlatformApi(fetcher);
    await api.validateQuery({
      metricIds: ['m1'],
      dimensions: [{ dimensionId: 'd1', granularity: 'MONTH' }],
      relationshipIds: [],
    });
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining('/queries:validate'),
      expect.objectContaining({ body: expect.not.stringContaining('datasetId') }),
    );
  });
  it('parses Problem Details', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ title: 'Conflict', status: 409, detail: 'stale version' }),
    });
    await expect(
      createHttpClient(fetcher)('/datasets/d', { method: 'PATCH' }),
    ).rejects.toMatchObject({
      problem: expect.objectContaining({ status: 409, detail: 'stale version' }),
    });
  });
});
