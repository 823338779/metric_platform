import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A } from './build-fixture';

const ENVIRONMENT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
test('环境配置独立于源码，构建历史进入固定目录和查询', async ({ page }) => {
  await buildFixture(page);
  let saved: any;
  let submitted: any;
  const calls: string[] = [];
  const build = () => ({
    buildId: BUILD_A,
    repository: submitted.repository,
    branchName: submitted.branchName,
    execution: saved.execution,
    configVersion: '1',
    commitSha: 'fixture-sha',
    buildStatus: 'SUCCEEDED',
    catalogAvailable: true,
    queryAvailable: true,
    initialDeployment: null,
  });
  await page.route('**/api/v4/dbt/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    const post = route.request().method() === 'POST';
    calls.push(path);
    if (path.endsWith('/environments')) {
      if (!post) return route.fulfill({ json: { items: saved ? [saved] : [] } });
      const { name, ...execution } = route.request().postDataJSON();
      expect(execution).not.toHaveProperty('repository');
      expect(execution).not.toHaveProperty('branchName');
      saved = {
        name,
        environmentId: ENVIRONMENT,
        configVersion: 1,
        execution: { ...execution, environmentId: ENVIRONMENT, configVersion: 1 },
      };
      return route.fulfill({ status: 201, json: saved });
    }
    if (path.endsWith(`/environments/${ENVIRONMENT}`)) return route.fulfill({ json: saved });
    if (path.endsWith(`/environments/${ENVIRONMENT}/builds`)) {
      if (post) submitted = route.request().postDataJSON();
      return route.fulfill({
        status: post ? 202 : 200,
        json: post ? build() : { items: submitted ? [build()] : [], nextCursor: null },
      });
    }
    if (path.endsWith(`/builds/${BUILD_A}`)) return route.fulfill({ json: build() });
    return route.fulfill({ status: 404, json: { detail: 'unexpected route' } });
  });
  await page.goto('/dbt/environments');
  await page.getByRole('button', { name: '新建环境' }).click();
  await page.getByLabel('环境名称').fill('集成环境');
  await page.getByLabel('物理实例 ID').fill('warehouse');
  await page.getByLabel('数据库主机').fill('localhost');
  await page.getByLabel('凭据引用').fill('warehouse-test');
  await page.getByLabel('Schema', { exact: true }).fill('env_browser');
  await page.getByRole('button', { name: '保存环境' }).click();
  await page.getByRole('link', { name: '集成环境' }).click();
  await page.getByLabel('Git 仓库地址').fill('https://example.test/metrics.git');
  await page.getByLabel('构建分支').fill('feature/browser');
  await page.getByRole('button', { name: '发起构建', exact: true }).click();
  await expect(page.getByRole('article').getByText('SUCCEEDED', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: '查看目录' }).click();
  await expect(page).toHaveURL(new RegExp(`/builds/${BUILD_A}/catalog`));
  await expect(page.getByText(/default_catalog \/ env_browser/)).toBeVisible();
  await page.getByRole('link', { name: '分析探索', exact: true }).last().click();
  await expect(page).toHaveURL(new RegExp(`/builds/${BUILD_A}/query`));
  await expect(page.getByRole('button', { name: '刷新此构建' })).toBeVisible();
  expect(submitted).toMatchObject({ branchName: 'feature/browser', expectedConfigVersion: 1 });
  expect(calls.some((path) => path.includes('agent'))).toBe(false);
});
