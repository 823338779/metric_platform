import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { authApi } from '../../api/authApi';
import { clearAuthSession } from '../../api/auth';
import { LoginPage } from './LoginPage';

const BUILD = '00000000-0000-4000-8000-000000000001';
const TOKEN = 'test-token';
const USER = 'test-user';
const PASSWORD = 'test-password';

function Destination() {
  const location = useLocation();
  return <p>{location.pathname + location.search}</p>;
}

beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
  }));
});

afterEach(() => {
  cleanup();
  clearAuthSession();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

// 经过实际登录组件跳转，不能只用直接goto绕过returnTo校验。
it.each(['catalog', 'query'])('returns to the fixed build %s after login', async (page) => {
  vi.spyOn(authApi, 'login').mockResolvedValue({
    accessToken: TOKEN,
    tokenType: 'Bearer',
    expiresIn: 3600,
    identity: {
      identityId: USER,
      subject: USER,
      displayName: USER,
      authVersion: 1,
      tenant_id: 'tn_00001',
      role: 'TENANT_MANAGER' as const,
    },
  });
  const destination = `/dbt/builds/${BUILD}/${page}?metricResourceId=opaque`;
  render(
    <MemoryRouter initialEntries={[`/login?returnTo=${encodeURIComponent(destination)}`]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Destination />} />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText('账号'), { target: { value: USER } });
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: PASSWORD } });
  fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));
  await waitFor(() => expect(screen.getByText(destination)).toBeTruthy());
  expect(authApi.login).toHaveBeenCalledWith(USER, PASSWORD);
});
