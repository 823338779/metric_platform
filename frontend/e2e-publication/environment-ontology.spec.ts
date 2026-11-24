import { expect, test } from '@playwright/test';
import { BUILD_A, buildFixture } from './build-fixture';
import type { ObjectType, Relation } from '../src/api/ontologyApi';

test('测试环境维护订单、客户和两个角色关系，生产环境仍为空', async ({ page }) => {
  await buildFixture(page);
  await page.route('**/api/dbt/repository/branches', (route) =>
    route.fulfill({ json: { repository: 'https://example.test/metrics.git', branches: ['main'] } }),
  );
  const testId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const prodId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  const environment = (id: string) => ({
    environmentId: id,
    name: id === testId ? '测试环境' : '生产环境',
    configVersion: 1,
    execution: {
      environmentId: id,
      configVersion: 1,
      connection: { type: 'postgres', instanceId: 'fixture' },
      catalog: 'warehouse',
      schema: 'public',
      threads: 1,
      businessTimezone: 'UTC',
    },
  });
  const objects: ObjectType[] = [],
    relations: Relation[] = [];
  let revision = 0;
  let refreshed = false,
    accepted = false;
  const observed = {
    physicalType: 'varchar(24)',
    observedAt: '2026-11-24T10:00:00Z',
    buildId: BUILD_A,
    table: {
      instanceId: 'fixture',
      catalog: 'default_catalog',
      schema: 'fixture',
      table: 'orders',
    },
  };
  const details = {
    columnName: 'order_id',
    declaredType: null,
    typeSource: 'WAREHOUSE',
    acceptedPhysicalType: 'bigint(20)',
    lastObservation: observed,
    reason: 'TYPE_CONFLICT',
  };
  await page.route('**/api/dbt/environments/**', async (route) => {
    const url = new URL(route.request().url());
    const [, id, resource] = url.pathname.match(/^\/api\/dbt\/environments\/([^/]+)(?:\/(.*))?$/)!;
    if (!resource) return route.fulfill({ json: environment(id) });
    if (resource === 'builds') return route.fulfill({ json: { items: [], nextCursor: null } });
    if (resource === 'ontology') {
      const isTest = id === testId;
      if (isTest && url.searchParams.get('refresh') === 'true') refreshed = true;
      return route.fulfill({
        json: {
          revision: isTest ? revision : 0,
          objects: isTest ? objects : [],
          relations: isTest ? relations : [],
          bindingReport: {
            buildId: isTest ? BUILD_A : null,
            revision: isTest ? revision : 0,
            findings:
              isTest && refreshed && objects.length
                ? [
                    {
                      kind: 'PROPERTY',
                      id: objects[0].primaryPropertyId,
                      status: accepted ? 'COMPATIBLE' : 'NEEDS_REPAIR',
                      reason: accepted ? 'MATCHED' : 'TYPE_CONFLICT',
                      evidence: details,
                    },
                    ...relations.map((r) => ({
                      kind: 'RELATION',
                      id: r.id,
                      status: 'NEEDS_REPAIR',
                      reason: 'TYPE_CONFLICT',
                      sourceEvidence: details,
                      targetEvidence: { ...details, columnName: 'customer_id' },
                    })),
                  ]
                : [],
          },
        },
      });
    }
    if (resource === 'ontology/datasets')
      return route.fulfill({
        json: {
          buildId: BUILD_A,
          datasets: [
            { assetId: 'orders', name: '订单数据集' },
            { assetId: 'customers', name: '客户数据集' },
          ],
        },
      });
    if (resource.endsWith('/columns')) {
      expect(url.searchParams.get('buildId')).toBe(BUILD_A);
      const assetId = resource.split('/')[2];
      return route.fulfill({
        json: {
          buildId: BUILD_A,
          assetId,
          columns: (assetId === 'orders' ? ['order_id', 'buyer_id'] : ['customer_id']).map(
            (name) => ({ name, declaredType: 'INT' }),
          ),
        },
      });
    }
    expect(id).toBe(testId);
    if (route.request().method() === 'DELETE') {
      expect(Number(url.searchParams.get('expectedRevision'))).toBe(revision);
      const relationId = resource.split('/').pop()!;
      const index = relations.findIndex((relation) => relation.id === relationId);
      expect(index).toBeGreaterThanOrEqual(0);
      relations.splice(index, 1);
      return route.fulfill({ json: { id: relationId, revision: ++revision } });
    }
    const body = route.request().postDataJSON();
    expect(body.expectedRevision).toBe(revision);
    expect(body.sourceBuildId).toBe(BUILD_A);
    if (body.acceptPhysicalTypes) {
      expect(body.acceptPhysicalTypes).toEqual([
        {
          propertyId: objects[0].primaryPropertyId,
          expectedBaselineType: 'bigint(20)',
          observedType: 'varchar(24)',
        },
      ]);
      expect(body.object.id).toBe(objects[0].id);
      accepted = true;
    } else if (resource === 'ontology/objects') objects.push(body.object);
    else if (resource === 'ontology/relations') relations.push(body.relation);
    else throw new Error(resource);
    return route.fulfill({ json: { id: (body.object ?? body.relation).id, revision: ++revision } });
  });
  await page.goto('/dbt/environments/' + testId);
  await page.getByRole('link', { name: '本体建模' }).click();
  const navigation = page.getByRole('navigation', { name: 'Metric Platform' });
  for (const tab of ['catalog', 'query']) {
    await page.goto('/dbt/builds/' + BUILD_A + '/' + tab);
    await expect(navigation.getByRole('link', { name: '本体建模' })).toHaveAttribute(
      'href',
      '/dbt/environments/' + testId + '/ontology',
    );
  }
  await navigation.getByRole('link', { name: '本体建模' }).click();
  await expect(navigation.getByRole('link', { name: '本体建模' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  const graph = page.getByRole('img', { name: '对象关系拓扑图' });
  for (const [name, asset, columns] of [
    ['订单', 'orders', ['order_id', 'buyer_id']],
    ['客户', 'customers', ['customer_id']],
  ] as const) {
    await page.getByRole('button', { name: '新建对象' }).click();
    await expect(page.getByRole('dialog', { name: '新建对象' })).toBeVisible();
    await page.getByLabel('绑定数据集').selectOption(asset);
    await page.getByLabel('对象名称').fill(name);
    for (const [index, column] of columns.entries()) {
      if (index) await page.getByRole('button', { name: '添加属性' }).click();
      await page.getByLabel('绑定列 ' + (index + 1)).selectOption(column);
      await page.getByLabel('属性名称 ' + (index + 1)).fill(column);
    }
    await page.getByLabel('声明主键').selectOption({ label: columns[0] });
    await page.getByRole('button', { name: '保存对象' }).click();
    await expect(graph.getByText(name, { exact: true })).toBeVisible();
  }
  expect(objects[0].primaryPropertyId).toBe(objects[0].properties[0].id);
  for (const name of ['下单客户', '收货人']) {
    await page.getByRole('button', { name: '新建关系' }).click();
    await page.getByLabel('关系名称').fill(name);
    await page.getByLabel('源对象').selectOption({ label: '订单' });
    await page.getByLabel('源属性').selectOption({ label: 'buyer_id' });
    await page.getByLabel('目标对象').selectOption({ label: '客户' });
    await page.getByLabel('目标属性').selectOption({ label: 'customer_id' });
    await page.getByLabel('声明基数').selectOption('MANY_TO_ONE');
    await page.getByRole('button', { name: '保存关系' }).click();
    await expect(graph.getByText(name, { exact: true })).toBeVisible();
  }
  await expect(graph.getByText('下单客户', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '重新核实' }).click();
  await graph.getByText('订单', { exact: true }).click();
  await page.getByRole('button', { name: '接受当前物理类型' }).click();
  await expect.poll(() => accepted).toBe(true);
  await graph.getByText('收货人', { exact: true }).click();
  await expect(page.getByText(/源端 order_id/).first()).toBeVisible();
  await expect(page.getByText(/目标端 customer_id/).first()).toBeVisible();
  await expect(page.getByLabel('关系名称')).toHaveValue('收货人');
  await expect(page.getByRole('dialog', { name: '编辑关系' })).toBeVisible();
  await page.getByRole('button', { name: '删除关系' }).click();
  await expect(page.getByRole('dialog', { name: '删除关系' })).toBeVisible();
  await page.getByRole('button', { name: '确认删除' }).click();
  await page.getByRole('button', { name: '管理定义' }).click();
  await expect(page.getByRole('button', { name: '编辑关系 下单客户' })).toBeVisible();
  await expect(page.getByRole('button', { name: '编辑关系 收货人' })).toHaveCount(0);
  await page.goto('/dbt/environments/' + prodId + '/ontology');
  await expect(page.getByText('生产环境', { exact: true })).toBeVisible();
  await expect(page.getByText('暂无对象')).toBeVisible();
  await expect(page.getByRole('button', { name: '编辑对象 订单' })).toHaveCount(0);
});

test('同一对象的上下级关系显示可点击的有向回环', async ({ page }) => {
  await buildFixture(page);
  const environmentId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const customer = {
    id: 'customer',
    name: '客户',
    description: '',
    datasetAssetId: 'customers',
    primaryPropertyId: 'id',
    properties: [
      { id: 'id', name: '客户编号', columnName: 'id', declaredType: 'INT' },
      { id: 'parent', name: '上级客户编号', columnName: 'parent_id', declaredType: 'INT' },
    ],
  };
  const relation = {
    id: 'parent-customer',
    name: '上级客户',
    description: '',
    cardinality: 'MANY_TO_ONE',
    sourceObjectId: 'customer',
    targetObjectId: 'customer',
    sourcePropertyId: 'parent',
    targetPropertyId: 'id',
  };
  await page.route('**/api/dbt/environments/' + environmentId, (route) =>
    route.fulfill({ json: { environmentId, name: '测试环境' } }),
  );
  await page.route('**/api/dbt/environments/' + environmentId + '/ontology', (route) =>
    route.fulfill({
      json: {
        revision: 2,
        objects: [customer],
        relations: [relation],
        bindingReport: { revision: 2, buildId: BUILD_A, findings: [] },
      },
    }),
  );
  await page.setViewportSize({ width: 600, height: 800 });
  await page.goto('/dbt/environments/' + environmentId + '/ontology');
  const graph = page.getByRole('img', { name: '对象关系拓扑图' });
  await expect
    .poll(() =>
      graph
        .locator('svg path[fill="none"][stroke]')
        .evaluateAll((paths) =>
          paths.reduce((sum, path) => sum + (path as SVGPathElement).getTotalLength(), 0),
        ),
    )
    .toBeGreaterThan(30);
  await graph.getByText('上级客户', { exact: true }).click();
  await expect(page.getByLabel('关系名称')).toHaveValue('上级客户');
});
