import { test, expect, type Page } from '@playwright/test';

const manager = {
  identityId: 'manager',
  subject: 'manager',
  displayName: '管理者',
  authVersion: 1,
  tenant_id: 'tn_00001',
  role: 'TENANT_MANAGER',
};
const reader = {
  ...manager,
  identityId: 'reader',
  subject: 'reader',
  displayName: '成员',
  role: 'READ_ONLY',
};

async function session(page: Page, current: typeof manager) {
  // 用旧的管理者会话快照验证刷新时信任 /me 返回的当前权限。
  await page.addInitScript(
    (identity) =>
      sessionStorage.setItem(
        'metric-platform.auth-session',
        JSON.stringify({ accessToken: 'fixture', tokenType: 'Bearer', expiresIn: 3600, identity }),
      ),
    manager,
  );
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: current }));
}

test('管理者创建只读账号并得到重名反馈', async ({ page }) => {
  await session(page, manager);
  let attempts = 0;
  await page.route('**/api/v1/tenant/accounts', (route) => {
    expect(route.request().postDataJSON()).toEqual({
      username: 'reader',
      password: 'fixture-password',
    });
    return ++attempts === 1
      ? route.fulfill({ status: 409, json: { status: 409 } })
      : route.fulfill({ status: 201, json: reader });
  });
  await page.goto('/tenant/accounts');
  await page.getByLabel('登录名').fill('reader');
  await page.getByLabel('初始密码').fill('fixture-password');
  await page.getByRole('button', { name: '创建只读账号' }).click();
  await expect(page.getByRole('status')).toHaveText('登录名已被使用');
  await page.getByRole('button', { name: '创建只读账号' }).click();
  await expect(page.getByRole('status')).toHaveText('只读账号已创建');
  await expect(page.getByLabel('初始密码')).toHaveValue('');
});

test('旧会话按当前只读身份显示空资产并保护管理路由', async ({ page }) => {
  await session(page, reader);
  await page.goto('/dbt/environments');
  await expect(page.getByText('暂无可见资产')).toBeVisible();
  await expect(page.getByRole('link', { name: '账号管理' })).toHaveCount(0);
  await page.goto('/tenant/accounts');
  await expect(page.getByText('无权访问此页面')).toBeVisible();
  await expect(page.getByRole('button', { name: '创建只读账号' })).toHaveCount(0);
});
