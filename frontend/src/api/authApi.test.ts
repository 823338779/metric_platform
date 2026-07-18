import { describe, expect, it, vi } from 'vitest';
import type { AuthIdentity } from './auth';
import { createAuthApi } from './authApi';

describe('authentication API', () => {
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
