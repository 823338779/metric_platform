# Metric Platform Logout Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a header logout control that clears the current Metric Platform browser session and returns the user to the login page.

**Architecture:** Keep logout entirely in the existing frontend authentication boundary. `PlatformLayout` reads the current identity through `getAuthSession()`, calls `clearAuthSession()` on logout, and uses React Router to replace the current route with `/login?reason=required`.

**Tech Stack:** React 19, React Router 7, Ant Design 6, TypeScript, Vitest, Testing Library

## Global Constraints

- Modify only the `metric-platform` project; do not change Data Agent.
- Keep username/password authentication and the existing ten-hour opaque access tokens.
- Do not add a backend logout endpoint, account settings, or an account dropdown.
- Do not create a Git commit unless the user explicitly requests one.

---

### Task 1: Header Logout Control

**Files:**
- Modify: `frontend/src/layout/PlatformLayout.tsx`
- Test: `frontend/src/layout/PlatformLayout.test.tsx`

**Interfaces:**
- Consumes: `getAuthSession(): AuthSession | null` and `clearAuthSession(): void` from `frontend/src/api/auth.ts`.
- Produces: a visible `登出` button whose click handler removes the session and calls `navigate('/login?reason=required', { replace: true })`.

- [ ] **Step 1: Write the failing component test**

Extend `PlatformLayout.test.tsx` with a test that saves an `AuthSession`, renders real routes, clicks the logout button, and verifies both storage clearing and navigation:

```tsx
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { clearAuthSession, getAuthSession, saveAuthSession } from '../api/auth';

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
  fireEvent.click(screen.getByRole('button', { name: '登出' }));

  expect(getAuthSession()).toBeNull();
  expect(await screen.findByText('登录页')).toBeTruthy();
  clearAuthSession();
});
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `frontend/`:

```powershell
npm.cmd test -- src/layout/PlatformLayout.test.tsx
```

Expected: FAIL because `PlatformLayout` does not yet render `Administrator` or a `登出` button.

- [ ] **Step 3: Implement the minimal header UI and handler**

Update `PlatformLayout.tsx` to import `Button` and `Space`, import the authentication helpers, read the current display name, and add the logout handler:

```tsx
import { Button, Layout, Menu, Space, Typography } from 'antd';
import { clearAuthSession, getAuthSession } from '../api/auth';

const displayName = getAuthSession()?.identity.displayName;
const handleLogout = () => {
  clearAuthSession();
  navigate('/login?reason=required', { replace: true });
};
```

Render the existing title and the account controls at opposite ends of the header:

```tsx
<Layout.Header
  style={{
    background: '#fff',
    padding: '0 24px',
    borderBottom: '1px solid #f0f0f0',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
  }}
>
  <Typography.Title level={4} style={{ margin: 0 }}>
    语义指标平台
  </Typography.Title>
  <Space>
    {displayName ? <Typography.Text>{displayName}</Typography.Text> : null}
    <Button onClick={handleLogout}>登出</Button>
  </Space>
</Layout.Header>
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run from `frontend/`:

```powershell
npm.cmd test -- src/layout/PlatformLayout.test.tsx
```

Expected: both `PlatformLayout` tests PASS.

- [ ] **Step 5: Run frontend regression verification**

Run from `frontend/`:

```powershell
npm.cmd test
npm.cmd run build
```

Expected: all Vitest tests pass; Prettier, TypeScript, and Vite production build succeed.

- [ ] **Step 6: Verify the live browser flow**

Against `http://localhost:5173/`, log in as `admin`, confirm the account name and `登出` button appear, click `登出`, and verify the login page appears and protected routes require login again.

- [ ] **Step 7: Verify repository scope**

Run:

```powershell
git -C E:\IdeaProjects\data-agent\metric-platform diff --check
git -C E:\IdeaProjects\data-agent\metric-platform diff --name-only
git -C E:\IdeaProjects\data-agent status --short
```

Expected: no whitespace errors; new logout changes are under `metric-platform`; no Data Agent source file was changed by this task.
