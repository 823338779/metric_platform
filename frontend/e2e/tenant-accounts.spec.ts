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
  await page.route('**/api/v1/assets', (route) => route.fulfill({ json: [] }));
  await page.goto('/dbt/environments');
  await expect(page.getByText('暂无可见资产')).toBeVisible();
  await expect(page.getByRole('link', { name: '账号管理' })).toHaveCount(0);
  await page.goto('/tenant/accounts');
  await expect(page.getByText('无权访问此页面')).toBeVisible();
  await expect(page.getByRole('button', { name: '创建只读账号' })).toHaveCount(0);
});

test('已授权指标可从我的资产查询，撤权后显示整条查询的错误', async ({ page }) => {
  await session(page, reader);
  const build = '12345678-1234-4234-8234-123456789abc';
  const nodeId = 'metric.shop.sales';
  await page.route('**/api/v1/assets', (route) =>
    route.fulfill({
      json: [
        {
          assetId: 'metric',
          nodeId,
          name: 'sales',
          kind: 'METRIC',
          dimensionName: '',
          buildId: build,
          granted: true,
          target: { repository: 'shop', environmentId: 'env', branchName: 'main' },
        },
      ],
    }),
  );
  await page.route('**/api/dbt/builds/*/catalog', (route) =>
    route.fulfill({
      json: [
        {
          unique_id: nodeId,
          resource_type: 'metric',
          name: 'sales',
        },
      ],
    }),
  );
  await page.route('**/available-dimensions', (route) =>
    route.fulfill({ json: { buildId: build, metrics: ['sales'], dimensions: [] } }),
  );
  let granted = true;
  await page.route('**/queries', (route) =>
    granted
      ? route.fulfill({
          json: {
            mode: 'QUERY',
            buildId: build,
            columns: [{ name: 'sales', type: 'INTEGER' }],
            rows: [[42]],
            resultTruncated: false,
          },
        })
      : route.fulfill({ status: 403, json: { status: 403, detail: '无权限使用所选指标或维度' } }),
  );
  await page.goto('/dbt/environments');
  await page.getByRole('link', { name: '查询汇总' }).click();
  const run = page.getByRole('button', { name: '查询', exact: true });
  await run.click();
  await expect(page.getByRole('cell', { name: '42', exact: true })).toBeVisible();
  granted = false;
  await run.click();
  await expect(page.getByText(/403：无权限使用所选指标或维度/)).toBeVisible();
  await expect(page.getByRole('cell', { name: '42', exact: true })).toHaveCount(0);
});
