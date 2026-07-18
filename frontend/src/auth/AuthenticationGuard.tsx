import { Spin } from 'antd';
import { useEffect, useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { clearAuthSession, getAuthSession } from '../api/auth';
import { authApi } from '../api/authApi';

type AuthenticationStatus = 'checking' | 'authenticated' | 'required' | 'expired';

export function AuthenticationGuard() {
  const location = useLocation();
  const [status, setStatus] = useState<AuthenticationStatus>(() =>
    getAuthSession() ? 'checking' : 'required',
  );

  useEffect(() => {
    if (status !== 'checking') return;

    let cancelled = false;
    void authApi.me().then(
      () => {
        if (!cancelled) setStatus('authenticated');
      },
      () => {
        clearAuthSession();
        if (!cancelled) setStatus('expired');
      },
    );

    return () => {
      cancelled = true;
    };
  }, [status]);

  if (status === 'authenticated') return <Outlet />;

  if (status === 'required' || status === 'expired') {
    const returnTo = `${location.pathname}${location.search}${location.hash}`;
    const search = new URLSearchParams({ reason: status, returnTo });
    return <Navigate to={`/login?${search}`} replace />;
  }

  return (
    <main className="authentication-loading" aria-label="正在验证登录状态">
      <Spin size="large" tip="正在验证登录状态">
        <span className="authentication-loading-content" />
      </Spin>
    </main>
  );
}
