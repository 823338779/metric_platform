import { cleanup, render, screen, fireEvent } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { TenantAccountsPage } from './TenantAccountsPage';
import { saveAuthSession, clearAuthSession } from '../../api/auth';

afterEach(() => {
  cleanup();
  clearAuthSession();
  vi.unstubAllGlobals();
});

it('creates a read-only account using the current manager session', async () => {
  saveAuthSession({
    accessToken: 'fixture',
    tokenType: 'Bearer',
    expiresIn: 3600,
    identity: {
      identityId: 'manager',
      subject: 'manager',
      displayName: '管理者',
      authVersion: 1,
      role: 'TENANT_MANAGER',
      tenant_id: 'tn_00001',
    },
  });
  const fetcher = vi.fn().mockResolvedValue(new Response('{}', { status: 201 }));
  vi.stubGlobal('fetch', fetcher);
  render(<TenantAccountsPage />);
  fireEvent.change(screen.getByLabelText('登录名'), { target: { value: 'reader' } });
  fireEvent.change(screen.getByLabelText('初始密码'), { target: { value: 'fixture-password' } });
  fireEvent.click(screen.getByRole('button', { name: '创建只读账号' }));
  expect(await screen.findByText('只读账号已创建')).toBeTruthy();
  expect(fetcher).toHaveBeenCalledWith(
    '/api/v1/tenant/accounts',
    expect.objectContaining({
      body: JSON.stringify({ username: 'reader', password: 'fixture-password' }),
    }),
  );
  expect((screen.getByLabelText('初始密码') as HTMLInputElement).value).toBe('');
});
