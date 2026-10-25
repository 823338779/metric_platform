import { expect, it, vi } from 'vitest';
import { createBuildCatalogApi } from '../../api/buildCatalogApi';
import { publishedResourceAdapter } from './publishedResourceAdapter';

const BUILD = 'build';
const ID = 'metric.shop.revenue';
const MANIFEST = {
  metrics: {
    [ID]: {
      unique_id: ID,
      resource_type: 'metric',
      name: 'revenue',
      label: '收入',
      type: 'simple',
    },
  },
  nodes: {},
  semantic_models: {},
};
it('原生 Manifest 在本地转换目录，不向目录接口发送分页或类型参数', async () => {
  const fetcher = vi.fn(
    async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(JSON.stringify(MANIFEST)),
  );
  const adapter = publishedResourceAdapter(createBuildCatalogApi(fetcher));
  const page = await adapter.getDbtCatalog('', BUILD, '', 0, 20, 'METRIC');
  expect(fetcher.mock.calls[0][0]).toBe('/api/dbt/builds/build/catalog');
  expect(page.resources).toEqual([
    expect.objectContaining({ resourceId: ID, name: 'revenue', kind: 'METRIC' }),
  ]);
});
it('血缘使用 selector 编码保留加号，并从原生节点建立依赖', async () => {
  const fetcher = vi.fn(
    async (input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(
        JSON.stringify(
          String(input).includes('/resources/')
            ? { buildId: BUILD, resourceId: ID, resource: MANIFEST.metrics[ID], source: null }
            : [
                {
                  unique_id: ID,
                  resource_type: 'metric',
                  name: 'revenue',
                  depends_on: { nodes: ['semantic_model.shop.orders'] },
                },
              ],
        ),
      ),
  );
  const lineage = await publishedResourceAdapter(createBuildCatalogApi(fetcher)).getDbtLineage(
    '',
    ID,
    BUILD,
  );
  expect(
    new URL(String(fetcher.mock.calls[1][0]), 'http://localhost').searchParams.get('selector'),
  ).toBe('+fqn:revenue+');
  expect(lineage.dependencies[0]).toMatchObject({
    upstreamResourceId: 'semantic_model.shop.orders',
    downstreamResourceId: ID,
  });
});
it('资源详情同时提供原生节点和封存源码，预览不发送指标字段', async () => {
  const fetcher = vi.fn(
    async (input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(
        JSON.stringify({
          buildId: BUILD,
          resourceId: String(input).includes('model.shop.orders') ? 'model.shop.orders' : ID,
          resource: String(input).includes('model.shop.orders')
            ? { name: 'orders', resource_type: 'model', fqn: ['shop', 'orders'] }
            : MANIFEST.metrics[ID],
          source: { path: 'models/metrics.yml', content: 'metrics: []' },
        }),
      ),
  );
  const adapter = publishedResourceAdapter(createBuildCatalogApi(fetcher));
  expect(await adapter.getDbtSource('', ID, BUILD)).toMatchObject({ content: 'metrics: []' });
  await adapter.previewDbtDataset('', 'model.shop.orders', BUILD, 10);
  expect(JSON.parse(fetcher.mock.calls[2][1]!.body as string)).toEqual({
    mode: 'PREVIEW',
    selector: 'fqn:shop.orders',
    limit: 10,
  });
});
it('EXPLAIN 只展示 SQL，不制造结果表或截断字段', async () => {
  const { publishedResult } = await import('./publishedResourceAdapter');
  const result = publishedResult({
    buildId: BUILD,
    commitSha: null,
    mode: 'EXPLAIN',
    businessTimezone: 'UTC',
    normalizedTimeRange: { startTime: null, endTime: null },
    sql: 'select 1',
  });
  expect(result.sql).toBe('select 1');
  expect(result.rows).toBeNull();
  expect(result.columns).toBeNull();
  expect(result.truncated).toBeNull();
});
it('同名数据源按原生包固定血缘 selector，不能跨包混入节点', async () => {
  const lineage = vi.fn().mockResolvedValue([]);
  const resource = vi.fn(async (_build: string, id: string) => ({
    buildId: BUILD,
    resourceId: id,
    resource: {
      resource_type: 'source',
      package_name: id.split('.')[1],
      source_name: 'raw',
      name: 'orders',
    },
    source: null,
  }));
  const { publishedResourceAdapter } = await import('./publishedResourceAdapter');
  const adapter = publishedResourceAdapter({ resource, lineage } as never);
  await adapter.getDbtLineage('', 'source.shop.raw.orders', BUILD);
  await adapter.getDbtLineage('', 'source.vendor.raw.orders', BUILD);
  expect(lineage.mock.calls).toEqual([
    [BUILD, '+source:shop.raw.orders+'],
    [BUILD, '+source:vendor.raw.orders+'],
  ]);
});
it('数据源缺少原生包身份时拒绝生成可能跨包匹配的 selector', async () => {
  const lineage = vi.fn();
  const resource = vi.fn().mockResolvedValue({
    buildId: BUILD,
    resourceId: 'source.shop.raw.orders',
    resource: { resource_type: 'source', source_name: 'raw', name: 'orders' },
    source: null,
  });
  const adapter = publishedResourceAdapter({ resource, lineage } as never);
  await expect(adapter.getDbtLineage('', 'source.shop.raw.orders', BUILD)).rejects.toThrow(
    '数据源缺少完整的原生名称',
  );
  expect(lineage).not.toHaveBeenCalled();
});
