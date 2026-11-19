import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { clearAuthSession, getAuthSession, saveAuthSession } from '../api/auth';
import { PlatformLayout } from './PlatformLayout';
import { afterEach, describe, expect, it } from 'vitest';

afterEach(() => {
  cleanup();
  clearAuthSession();
});

describe('PlatformLayout', () => {
  it('collapses navigation without losing fixed build destinations', () => {
    saveAuthSession({
      accessToken: 'fixture',
      tokenType: 'Bearer',
      expiresIn: 3600,
      identity: {
        identityId: 'manager',
        subject: 'manager',
        displayName: 'Manager',
        authVersion: 1,
        tenant_id: 'tn_00001',
        role: 'TENANT_MANAGER',
      },
    });
    render(
      <MemoryRouter initialEntries={['/dbt/builds/build-123/query?resourceId=metric-1']}>
        <Routes>
          <Route path="/dbt/builds/:buildId/query" element={<PlatformLayout />} />
        </Routes>
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: '收起导航' }));
    expect(screen.getByRole('button', { name: '展开导航' }).getAttribute('aria-expanded')).toBe(
      'false',
    );
    expect(screen.getAllByRole('link', { name: '资源管理' })[0].getAttribute('href')).toBe(
      '/dbt/builds/build-123/catalog?resourceId=metric-1',
    );
    expect(screen.getByRole('link', { name: '执行环境' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '展开导航' }));
    expect(screen.getByRole('button', { name: '收起导航' }).getAttribute('aria-expanded')).toBe(
      'true',
    );
  });

  it('shows environment navigation without the removed deployment page', () => {
    render(
      <MemoryRouter>
        <PlatformLayout />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('link', { name: '部署版本' })).toBeNull();
    expect(screen.getByRole('link', { name: '执行环境' }).getAttribute('href')).toBe(
      '/dbt/environments',
    );
    expect(screen.queryByText('数据源')).toBeNull();
    expect(screen.queryByText('数据集')).toBeNull();
    expect(screen.queryByText('指标')).toBeNull();
    expect(screen.queryByText('维度')).toBeNull();
    expect(screen.queryByText('查询工作台')).toBeNull();
  });

  it('clears the session and navigates to login when the user logs out', async () => {
    saveAuthSession({
      accessToken: 'token',
      tokenType: 'Bearer',
      expiresIn: 36000,
      identity: {
        identityId: 'admin-id',
        subject: 'admin',
        displayName: 'Administrator',
        authVersion: 1,
        tenant_id: 'tn_00001',
        role: 'TENANT_MANAGER' as const,
      },
    });

    render(
      <MemoryRouter initialEntries={['/datasets']}>
        <Routes>
          <Route path="/login" element={<div>登录页</div>} />
          <Route path="*" element={<PlatformLayout />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText('Administrator')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /登\s*出/ }));

    expect(getAuthSession()).toBeNull();
    expect(await screen.findByText('登录页')).toBeTruthy();
  });
});
