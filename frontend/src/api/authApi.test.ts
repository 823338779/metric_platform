import { describe, expect, it, vi } from 'vitest';
import { safeReturnTo, type AuthIdentity } from './auth';
import { createAuthApi } from './authApi';

describe('authentication API', () => {
  it('returns to dbt projects after login by default', () => {
    expect(safeReturnTo(null)).toBe('/dbt/projects');
    expect(safeReturnTo('/metrics')).toBe('/dbt/projects');
    expect(safeReturnTo('/dbt/projects/sales/catalog?buildId=one')).toBe(
      '/dbt/projects/sales/catalog?buildId=one',
    );
  });

  it.each(['catalog', 'query'])('preserves a fixed build %s return URL', (page) => {
    const destination = `/dbt/builds/00000000-0000-4000-8000-000000000001/${page}?metricResourceId=opaque%2Fid#details`;
    expect(safeReturnTo(destination)).toBe(destination);
  });

  it.each([
    'https://outside.invalid/dbt/builds/00000000-0000-4000-8000-000000000001/catalog',
    '//outside.invalid/dbt/builds/00000000-0000-4000-8000-000000000001/catalog',
    '/\\outside.invalid/catalog',
    'javascript:alert(1)',
    '/%2f%2foutside.invalid/catalog',
    '/dbt/builds/../projects/catalog',
    '/dbt/builds/%2e%2e/catalog',
    '/dbt/builds/00000000-0000-4000-8000-000000000001/catalog/../../outside',
    '/dbt/builds/00000000-0000-4000-8000-000000000001/unknown',
  ])('rejects an unsafe or unsupported login destination: %s', (destination) => {
    expect(safeReturnTo(destination)).toBe('/dbt/projects');
  });

  it('uses the authentication-only identity contract', async () => {
    const identity: AuthIdentity = {
      identityId: 'user-1',
      subject: 'admin',
      displayName: 'Platform Administrator',
      authVersion: 2,
    };
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        accessToken: 'token',
        tokenType: 'Bearer',
        expiresIn: 36_000,
        identity,
      }),
    });

    const session = await createAuthApi(fetcher).login('admin', 'admin');

    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining('/auth/token'),
      expect.objectContaining({ body: JSON.stringify({ username: 'admin', password: 'admin' }) }),
    );
    expect(session.identity).toEqual(identity);
  });
});
