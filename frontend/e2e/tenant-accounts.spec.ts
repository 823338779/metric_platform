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
  await page.route('**/api/dbt/environments', (route) => route.fulfill({ json: { items: [] } }));
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: current }));
}

test('管理者创建只读账号并得到重名反馈', async ({ page }) => {
  await session(page, manager);
  let attempts = 0;
  await page.route('**/api/v1/tenant/accounts', (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        json: attempts > 1 ? [{ id: reader.identityId, username: reader.subject }] : [],
      });
    }
    expect(route.request().postDataJSON()).toEqual({
      username: 'reader',
      password: 'fixture-password',
    });
    return ++attempts === 1
      ? route.fulfill({ status: 409, json: { status: 409 } })
      : route.fulfill({ status: 201, json: reader });
  });
  await page.route('**/api/v1/tenant/accounts/reader/assets', (route) =>
    route.fulfill({ json: [] }),
  );
  await page.goto('/tenant/accounts');
  await page.getByRole('button', { name: '新建账号' }).click();
  await page.getByLabel('登录名').fill('reader');
  await page.getByLabel('初始密码').fill('fixture-password');
  await page.getByRole('button', { name: '创建只读账号' }).click();
  await expect(page.getByRole('status')).toHaveText('登录名已被使用');
  await page.getByRole('button', { name: '创建只读账号' }).click();
  await expect(page.getByRole('status')).toHaveText('只读账号已创建');
  await expect(page.getByRole('heading', { name: 'reader' })).toBeVisible();
  await page.getByRole('button', { name: '新建账号' }).click();
  await expect(page.getByLabel('初始密码')).toHaveValue('');
});

test('旧会话按当前只读身份显示空资产并保护管理路由', async ({ page }) => {
  await session(page, reader);
  await page.route('**/api/v1/assets', (route) => route.fulfill({ json: [] }));
  await page.goto('/dbt/environments');
  await expect(page.getByRole('heading', { name: '执行环境', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '执行环境' })).toBeVisible();
  await expect(page.getByText('我的资产')).toHaveCount(0);
  await expect(page.getByRole('link', { name: '账号管理' })).toHaveCount(0);
  await page.goto('/tenant/accounts');
  await expect(page.getByText('无权访问此页面')).toBeVisible();
  await expect(page.getByRole('button', { name: '创建只读账号' })).toHaveCount(0);
});

test('已授权指标可从资源目录查询，撤权后显示整条查询的错误', async ({ page }) => {
  await session(page, reader);
  const build = '12345678-1234-4234-8234-123456789abc';
  const nodeId = 'metric.shop.sales';
  await page.route('**/api/dbt/builds/' + build, (route) =>
    route.fulfill({
      json: {
        buildId: build,
        catalogAvailable: true,
        repository: 'shop',
        branchName: 'main',
        execution: { environmentId: 'env', catalog: 'warehouse', schema: 'prod', configVersion: 1 },
      },
    }),
  );
  await page.route('**/api/dbt/deployments/current?**', (route) =>
    route.fulfill({ json: { activeBuildId: build } }),
  );
  await page.route('**/api/dbt/builds?**', (route) =>
    route.fulfill({ json: { items: [], nextCursor: null } }),
  );
  await page.route('**/api/dbt/deployments?**', (route) =>
    route.fulfill({ json: { items: [], nextCursor: null } }),
  );
  await page.route('**/api/dbt/builds/*/catalog', (route) =>
    route.fulfill({
      json: [
        {
          unique_id: nodeId,
          resource_type: 'metric',
          name: 'sales',
          granted: true,
        },
        { unique_id: 'metric.shop.cost', resource_type: 'metric', name: 'cost', granted: false },
        {
          unique_id: 'region-id',
          resource_type: 'dimension',
          name: 'region',
          semanticModelName: 'orders',
          granted: false,
        },
        {
          unique_id: 'model.shop.orders',
          resource_type: 'model',
          name: 'orders',
          granted: true,
          selector: 'fqn:shop.orders',
        },
      ],
    }),
  );
  await page.route('**/api/dbt/builds/*/resources/' + nodeId, (route) =>
    route.fulfill({
      json: {
        buildId: build,
        resourceId: nodeId,
        resource: { unique_id: nodeId, resource_type: 'metric', name: 'sales' },
        source: { path: 'metrics.yml', content: 'name: sales' },
      },
    }),
  );
  await page.route('**/api/dbt/builds/*/lineage?**', (route) => route.fulfill({ json: [] }));
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
  const execution = {
    environmentId: 'env',
    configVersion: 1,
    connection: { type: 'starrocks', instanceId: 'warehouse' },
    catalog: 'warehouse',
    schema: 'prod',
    businessTimezone: 'Asia/Shanghai',
    threads: 2,
  };
  const environment = { environmentId: 'env', name: '生产环境', configVersion: 1, execution };
  await page.route('**/api/dbt/environments', (route) =>
    route.fulfill({
      json: {
        items: [environment, { ...environment, environmentId: 'test-env', name: '测试环境' }],
      },
    }),
  );
  await page.route('**/api/dbt/environments/env', (route) => route.fulfill({ json: environment }));
  await page.route('**/api/dbt/environments/env/builds*', (route) =>
    route.fulfill({
      json: {
        items: [
          {
            buildId: build,
            branchName: 'main',
            buildStatus: 'SUCCEEDED',
            catalogAvailable: true,
            execution,
          },
        ],
        nextCursor: null,
      },
    }),
  );
  await page.goto('/dbt/environments');
  await expect(page.getByRole('link', { name: '测试环境', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '新建环境' })).toHaveCount(0);
  await page.getByRole('link', { name: '生产环境', exact: true }).click();
  await expect(page.getByText('发起构建', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '重新构建' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '编辑配置' })).toHaveCount(0);
  await page.getByRole('link', { name: '进入构建 ' + build }).click();
  for (const [name, access] of [
    ['cost', '无权限'],
    ['region', '无权限'],
    ['orders', '有权限'],
    ['sales', '有权限'],
  ]) {
    await expect(
      page
        .locator('.resource-tree-leaf')
        .filter({
          has: page
            .locator('.resource-tree-leaf-name')
            .filter({ hasText: new RegExp('^' + name + '$') }),
        })
        .getByText(access, { exact: true }),
    ).toBeVisible();
  }
  await page.getByText('sales', { exact: true }).click();
  await page.getByRole('button', { name: /开始分析/ }).click();
  await expect(page.getByRole('link', { name: '返回资源目录' })).toHaveAttribute(
    'href',
    new RegExp(build + '/catalog'),
  );
  await expect(
    page.getByRole('navigation', { name: '工作区' }).getByRole('link', { name: '分析探索' }),
  ).toBeVisible();
  await expect(page.getByRole('link', { name: '账号管理' })).toHaveCount(0);
  const run = page.getByRole('button', { name: '查询', exact: true });
  await run.click();
  await expect(page.getByRole('cell', { name: '42', exact: true })).toBeVisible();
  granted = false;
  await run.click();
  await expect(page.getByText(/403：无权限使用所选指标或维度/)).toBeVisible();
  await expect(page.getByRole('cell', { name: '42', exact: true })).toHaveCount(0);
});
