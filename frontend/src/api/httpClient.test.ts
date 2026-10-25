import { describe, expect, it, vi } from 'vitest';
import { ApiProblem, createHttpClient } from './httpClient';
import { createBuildCatalogApi } from './buildCatalogApi';

describe('typed API client', () => {
  it('submits a versioned typed query without SQL', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, json: async () => ({ valid: true }) });
    const api = createBuildCatalogApi(fetcher);
    await api.submit('11111111-1111-4111-8111-111111111111', {
      mode: 'QUERY',
      metrics: ['revenue'],
    });
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining('/builds/11111111-1111-4111-8111-111111111111/queries'),
      expect.objectContaining({
        body: JSON.stringify({
          mode: 'QUERY',
          metrics: ['revenue'],
        }),
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
      createHttpClient(fetcher)('/builds/11111111-1111-4111-8111-111111111111/catalog'),
    ).rejects.toMatchObject({
      problem: expect.objectContaining({ status: 409, detail: 'stale version' }),
    });
  });
});
