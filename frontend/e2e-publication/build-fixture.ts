import type { Page } from '@playwright/test';

export const BUILD_A = '11111111-1111-4111-8111-111111111111';
export const BUILD_B = '22222222-2222-4222-8222-222222222222';
export const BUILD_MAIN = '33333333-3333-4333-8333-333333333333';
const IDENTITY = {
  identityId: 'fixture',
  subject: 'fixture',
  displayName: '验收账号',
  authVersion: 1,
  tenant_id: 'tn_00001',
  role: 'TENANT_MANAGER' as const,
};
type Json = Record<string, any>;
/** 完整执行快照 fixture：保留独立构建、同步选项/查询和构建分页身份，未知路由明确失败。 */
export async function buildFixture(
  page: Page,
  config: {
    resources?: Json[] | ((id: string) => Json[]);
    options?: Json[];
    source?: string;
    dependencies?: Json[];
    result?: (body: Json, buildId: string) => Json;
    resource?: (id: string) => Json;
  } = {},
) {
  await page.addInitScript(
    (identity) =>
      sessionStorage.setItem(
        'metric-platform.auth-session',
        JSON.stringify({
          accessToken: 'test-token',
          tokenType: 'Bearer',
          expiresIn: 36000,
          identity,
        }),
      ),
    IDENTITY,
  );
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: IDENTITY }));
  const requests: { path: string; body?: Json }[] = [];
  let lost = false;
  let active = BUILD_A;
  const build = (id: string) => ({
    buildId: id,
    repository: 'repo/a',
    branchName: id === BUILD_MAIN ? 'main' : id === BUILD_B ? 'b' : 'a',
    execution: {
      environmentId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      configVersion: 1,
      connection: {
        type: 'postgres',
        instanceId: 'fixture',
        host: 'localhost',
        port: 5432,
        path: null,
      },
      credentialRef: 'fixture',
      catalog: 'warehouse',
      schema: 'analytics',
      threads: 1,
      businessTimezone: 'UTC',
    },
    commitSha: 'sha-' + id,
    buildStatus: 'SUCCEEDED',
    catalogAvailable: true,
  });
  const resources = (id: string) =>
    (typeof config.resources === 'function'
      ? config.resources(id)
      : (config.resources ?? [
          {
            resourceId: 'same',
            name: 'same',
            displayName:
              id === BUILD_B ? '销售额 b' : id === BUILD_MAIN ? '销售额 main' : '销售额 a',
            kind: 'METRIC',
          },
        ])
    ).map((item) => ({
      nativeId: item.resourceId,
      displayName: item.name,
      capabilities: ['QUERY'],
      attributes: {},
      nativeDetails: {},
      sourcePath: 'models/metrics.yml',
      ...item,
    }));
  const native = (item: Json) => ({
    ...item.attributes,
    ...item.nativeDetails,
    unique_id: item.resourceId,
    resource_type:
      item.kind === 'METRIC'
        ? 'metric'
        : item.kind === 'SEMANTIC_MODEL'
          ? 'semantic_model'
          : 'model',
    name: item.name,
    label: item.displayName,
    description: item.description,
    original_file_path: item.sourcePath ?? 'models/metrics.yml',
    fqn: ['fixture', item.name],
    config: { materialized: item.kind === 'VIEW' ? 'view' : 'table' },
  });
  await page.route('**/api/dbt/**', async (route) => {
    const url = new URL(route.request().url());
    const path = decodeURIComponent(url.pathname);
    const body = route.request().method() === 'POST' ? route.request().postDataJSON() : undefined;
    requests.push({ path, body });
    const id = path.match(/\/builds\/([^/]+)/)?.[1] ?? active;
    if (path.endsWith('/deployments/current')) {
      const branch = url.searchParams.get('branchName');
      return route.fulfill({
        json: {
          activeBuildId:
            branch === 'empty'
              ? null
              : branch === 'main'
                ? BUILD_MAIN
                : branch === 'b'
                  ? BUILD_B
                  : active,
          sourceState: 'CURRENT',
        },
      });
    }
    if (path.endsWith('/deployments'))
      return route.fulfill({ json: { items: [], nextCursor: null } });
    if (path.endsWith('/builds'))
      return route.fulfill({
        json: {
          items: [build(BUILD_A), build(BUILD_B)],
          nextCursor: null,
          source: 'SERVICE_CHANGE_FEED',
          syncedAt: '2026-10-10',
        },
      });
    if (/\/builds\/[^/]+$/.test(path)) return route.fulfill({ json: build(id) });
    if (path.endsWith('/catalog')) {
      return route.fulfill({
        json: resources(id).map(native),
      });
    }
    if (path.endsWith('/lineage')) {
      const edges = config.dependencies ?? [];
      const ids = [
        ...new Set(edges.flatMap((edge) => [edge.upstreamResourceId, edge.downstreamResourceId])),
      ];
      return route.fulfill({
        json: ids.map((unique_id) => ({
          unique_id,
          depends_on: {
            nodes: edges
              .filter((edge) => edge.downstreamResourceId === unique_id)
              .map((edge) => edge.upstreamResourceId),
          },
        })),
      });
    }
    if (path.endsWith('/available-dimensions')) {
      return route.fulfill({
        status: 200,
        json: {
          buildId: id,
          dimensions: (config.options ?? []).map(({ resourceId: _resourceId, ...option }) => ({
            dimensionType: option.granularities.length ? 'time' : 'categorical',
            ...option,
          })),
          metrics: body.metrics,
        },
      });
    }
    if (path.endsWith('/queries')) {
      const allowed =
        body.mode === 'PREVIEW'
          ? ['mode', 'selector', 'limit']
          : body.mode === 'DIMENSION_VALUES'
            ? ['mode', 'metrics', 'dimensionOptionId', 'filters', 'startTime', 'endTime', 'limit']
            : ['mode', 'metrics', 'groupBy', 'orderBy', 'filters', 'startTime', 'endTime', 'limit'];
      if (
        Object.keys(body).some((key) => !allowed.includes(key)) ||
        (body.mode !== 'PREVIEW' && !body.metrics?.length)
      )
        return route.fulfill({ status: 422, json: { code: 'INVALID_REQUEST' } });
      if (lost) {
        lost = false;
        return route.abort('failed');
      }
      const custom = config.result?.(body, id) ?? {};
      const identity = { buildId: id, commitSha: 'sha-' + id, mode: body.mode };
      const time = {
        businessTimezone: 'UTC',
        normalizedTimeRange: { startTime: null, endTime: null },
      };
      return route.fulfill({
        status: 200,
        json:
          body.mode === 'EXPLAIN'
            ? { ...identity, ...time, sql: custom.sql ?? 'select 1' }
            : {
                ...identity,
                ...(body.mode === 'PREVIEW' ? {} : time),
                columns: custom.columns ?? [{ name: '销售额', type: 'DECIMAL' }],
                rows: custom.rows ?? [[id === BUILD_B ? '31.00' : '62.00']],
                resultTruncated: custom.resultTruncated ?? false,
              },
      });
    }
    const rid = path.match(/\/resources\/([^/]+)$/)?.[1];
    if (rid) {
      const item = resources(id).find((item) => item.resourceId === rid) ?? config.resource?.(rid);
      if (!item) return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND' } });
      return route.fulfill({
        json: {
          buildId: id,
          resourceId: rid,
          resource: native(item),
          source: {
            path: item.sourcePath ?? 'models/metrics.yml',
            content: config.source ?? 'metrics:\n  - name: ' + item.name + '\n    type: simple\n',
          },
        },
      });
    }
    return route.fulfill({ status: 404, json: { code: 'UNKNOWN_FIXTURE_ROUTE', detail: path } });
  });
  return {
    requests,
    loseNextReceipt: () => {
      lost = true;
    },
    advance: () => {
      active = BUILD_B;
    },
  };
}
