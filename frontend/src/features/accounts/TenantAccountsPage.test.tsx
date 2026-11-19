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
  let created = false;
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    if (init.method === 'POST') {
      created = true;
      return Response.json({ identityId: 'new-reader' }, { status: 201 });
    }
    if (url.endsWith('/accounts')) {
      const accounts = [{ id: 'existing', username: 'existing' }];
      if (created) accounts.push({ id: 'new-reader', username: 'reader' });
      return Response.json(accounts);
    }
    return Response.json([]);
  });
  vi.stubGlobal('fetch', fetcher);
  render(<TenantAccountsPage />);
  fireEvent.click(screen.getByRole('button', { name: '新建账号' }));
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
  expect(await screen.findByRole('heading', { name: 'reader' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '新建账号' }));
  expect((screen.getByLabelText('初始密码') as HTMLInputElement).value).toBe('');
});
