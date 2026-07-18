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
  it('shows resource centers and workbench', () => {
    render(
      <MemoryRouter>
        <PlatformLayout />
      </MemoryRouter>,
    );
    expect(screen.getByText('数据集')).toBeTruthy();
    expect(screen.getByText('指标')).toBeTruthy();
    expect(screen.getByText('维度')).toBeTruthy();
    expect(screen.getByText('查询工作台')).toBeTruthy();
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
