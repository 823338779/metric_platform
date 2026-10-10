import type { Page } from '@playwright/test';

export const BUILD_A = '11111111-1111-4111-8111-111111111111';
export const BUILD_B = '22222222-2222-4222-8222-222222222222';
export const BUILD_MAIN = '33333333-3333-4333-8333-333333333333';
const OPTIONS_ID = '44444444-4444-4444-8444-444444444444';
const IDENTITY = {
  identityId: 'fixture',
  subject: 'fixture',
  displayName: '验收账号',
  authVersion: 1,
};
type Json = Record<string, any>;
/** 唯一 v3 fixture：保留独立构建、异步选项/查询和分页身份，未知路由明确失败。 */
export async function buildFixture(
  page: Page,
  config: {
    resources?: Json[] | ((id: string) => Json[]);
    options?: Json[];
    source?: string;
    dependencies?: Json[];
    result?: (body: Json, buildId: string) => Json;
    resource?: (id: string) => Json;
    pageSize?: number;
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
  const queries = new Map<string, Json>();
  let lost = false;
  let active = BUILD_A;
  let serial = 0;
  let optionBuild = BUILD_A;
  const build = (id: string) => ({
    buildId: id,
    repository: 'repo/a',
    branchName: id === BUILD_MAIN ? 'main' : id === BUILD_B ? 'b' : 'a',
    environment: id === BUILD_MAIN ? 'PRODUCTION' : 'PREVIEW',
    commitSha: 'sha-' + id,
    buildStatus: 'SUCCEEDED',
    catalogAvailable: true,
    queryAvailable: true,
    queryUnavailableReason: null,
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
  await page.route('**/api/v3/dbt/**', async (route) => {
    const url = new URL(route.request().url());
    const path = decodeURIComponent(url.pathname);
    const body = route.request().method() === 'POST' ? route.request().postDataJSON() : undefined;
    requests.push({ path, body });
    const id = path.match(/\/builds\/([^/]+)/)?.[1] ?? active;
    if (path.endsWith('/projects'))
      return route.fulfill({
        json: {
          items: [
            { projectId: 'p', name: '演示项目', repository: 'repo/a', developmentConfigured: true },
          ],
          nextCursor: null,
        },
      });
    if (path.endsWith('/branches'))
      return route.fulfill({
        json: {
          items: ['main', 'a', 'b', 'empty'].map((branchName) => ({ branchName, headSha: 'sha' })),
          source: 'AGENT',
          cached: false,
          observedAt: '2026-10-10',
        },
      });
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
      const items = resources(id).filter(
        (item) => !url.searchParams.get('kind') || item.kind === url.searchParams.get('kind'),
      );
      const offset = Number(url.searchParams.get('cursor') ?? 0);
      const size = config.pageSize ?? Number(url.searchParams.get('limit') ?? 50);
      return route.fulfill({
        json: {
          buildId: id,
          items: items.slice(offset, offset + size),
          nextCursor: offset + size < items.length ? String(offset + size) : null,
        },
      });
    }
    if (path.endsWith('/query-options')) {
      optionBuild = id;
      return route.fulfill({
        status: 202,
        json: {
          optionsTaskId: OPTIONS_ID,
          buildId: id,
          state: 'QUEUED',
          options: [],
          metricResourceIds: body.metricResourceIds,
        },
      });
    }
    if (path.includes('/query-options/'))
      return route.fulfill({
        json: {
          optionsTaskId: OPTIONS_ID,
          buildId: optionBuild,
          state: 'SUCCEEDED',
          options: config.options ?? [],
        },
      });
    if (path.endsWith('/queries')) {
      if (!queries.has(body.idempotencyKey)) {
        serial++;
        queries.set(body.idempotencyKey, {
          queryId: `55555555-5555-4555-8555-${String(serial).padStart(12, '0')}`,
          buildId: id,
          state: 'SUCCEEDED',
          mode: body.mode,
          resultAvailable: true,
          columns: [{ name: '销售额', type: 'DECIMAL' }],
          rows: [[id === BUILD_B ? '31.00' : '62.00']],
          nextOffset: null,
          ...config.result?.(body, id),
        });
      }
      if (lost) {
        lost = false;
        return route.abort('failed');
      }
      const value = queries.get(body.idempotencyKey)!;
      return route.fulfill({
        status: 202,
        json: { queryId: value.queryId, buildId: value.buildId, state: 'QUEUED' },
      });
    }
    if (/\/queries\/[^/]+\/(status|results)$/.test(path)) {
      const q = path.split('/').at(-2);
      const value = [...queries.values()].find((value) => value.queryId === q);
      return value
        ? route.fulfill({ json: value })
        : route.fulfill({ status: 404, json: { code: 'QUERY_NOT_FOUND' } });
    }
    const rid = path.match(/\/resources\/([^/]+)/)?.[1];
    if (rid) {
      const item = resources(id).find((item) => item.resourceId === rid) ?? config.resource?.(rid);
      if (!item) return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND' } });
      if (path.endsWith('/lineage'))
        return route.fulfill({
          json: { buildId: id, resourceId: rid, dependencies: config.dependencies ?? [] },
        });
      if (path.endsWith('/source'))
        return route.fulfill({
          json: {
            buildId: id,
            resourceId: rid,
            path: item.sourcePath ?? 'models/metrics.yml',
            content: config.source ?? 'metrics:\n  - name: ' + item.name + '\n    type: simple\n',
          },
        });
      if (path.endsWith('/native-details'))
        return route.fulfill({
          json: { buildId: id, resourceId: rid, nativeDetails: item.nativeDetails },
        });
      return route.fulfill({
        json: { buildId: id, capabilities: [], attributes: {}, nativeDetails: {}, ...item },
      });
    }
    return route.fulfill({ status: 404, json: { code: 'UNKNOWN_FIXTURE_ROUTE', detail: path } });
  });
  return {
    requests,
    queries,
    loseNextReceipt: () => {
      lost = true;
    },
    advance: () => {
      active = BUILD_B;
    },
  };
}
