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
  it('shows only dbt project navigation', () => {
    render(
      <MemoryRouter>
        <PlatformLayout />
      </MemoryRouter>,
    );
    expect(screen.getByText('dbt 项目')).toBeTruthy();
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
