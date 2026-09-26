import { describe, expect, it, vi } from 'vitest';
import { safeReturnTo, type AuthIdentity } from './auth';
import { createAuthApi } from './authApi';

describe('authentication API', () => {
  it('returns to dbt projects after login by default', () => {
    expect(safeReturnTo(null)).toBe('/dbt/projects');
    expect(safeReturnTo('/metrics')).toBe('/dbt/projects');
    expect(safeReturnTo('/dbt/projects/sales/catalog?releaseId=one')).toBe(
      '/dbt/projects/sales/catalog?releaseId=one',
    );
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
