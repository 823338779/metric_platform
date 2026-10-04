import type { Page } from '@playwright/test';

/** 有状态 HTTP fixture：同名资源跨分支取不同结果，构建失败保留旧发布。 */
export async function branchFixture(page: Page) {
  const identity = {
    identityId: 'fixture',
    subject: 'fixture',
    displayName: '分支验收',
    authVersion: 1,
  };
  await page.addInitScript(
    (user) =>
      sessionStorage.setItem(
        'metric-platform.auth-session',
        JSON.stringify({
          accessToken: 'fixture',
          tokenType: 'Bearer',
          expiresIn: 36000,
          identity: user,
        }),
      ),
    identity,
  );
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: identity }));
  const requests: { path: string; body?: Record<string, unknown> }[] = [];
  let loseReceipt = false;
  const queries = new Map<
    string,
    {
      queryId: string;
      projectId: string;
      branchId: string;
      releaseId: string;
      state: string;
      columns: { name: string; type: string }[];
      rows: string[][];
    }
  >();
  const branches = ['main', 'a', 'b'].map((id) => ({
    projectId: 'p',
    branchId: id,
    gitRef: 'refs/heads/' + id,
    mode: id === 'main' ? 'PRODUCTION' : 'PREVIEW',
    status: 'ACTIVE',
    baseCommitSha: 'base',
    baseReleaseId: 'r-main',
    observedHeadSha: 'sha-' + id,
    activeReleaseId: 'r-' + id,
    latestReleaseId: id === 'b' ? 'failed-b' : 'r-' + id,
    version: 1,
    mergeRequestUrl: null,
  }));
  await page.route('**/api/v2/semantic-projects/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const body = route.request().method() === 'POST' ? route.request().postDataJSON() : undefined;
    requests.push({ path, body });
    const id = path.match(/\/branches\/([^/]+)/)?.[1];
    if (path.endsWith('/branches')) return route.fulfill({ json: branches });
    const branch = branches.find((item) => item.branchId === id);
    if (!branch) return route.fulfill({ status: 404, json: { status: 404 } });
    const version = 'r-' + id;
    const release = {
      projectId: 'p',
      branchId: id,
      releaseId: version,
      sourceSha: 'available-' + id,
      state: 'PUBLISHED',
      publicationSequence: 1,
      catalogSchemaVersion: 1,
    };
    const resource = {
      branchId: id,
      releaseId: version,
      resourceId: 'same',
      nativeId: 'metric.p.same',
      kind: 'METRIC',
      name: 'same',
      displayName: '销售额 ' + id,
      description: null,
      sourcePath: 'models/metrics.yml',
      capabilities: ['QUERY'],
      attributes: {
        metricType: 'simple',
        definitionSummary: '分支 ' + id,
        inputMetricIds: [],
        datasetIds: [],
      },
      nativeDetails: {},
    };
    if (path.endsWith('/branches/' + id)) return route.fulfill({ json: branch });
    if (path.endsWith('/publication'))
      return route.fulfill({ json: { projectId: 'p', branchId: id, activePublication: release } });
    if (path.endsWith('/releases'))
      return route.fulfill({
        json:
          id === 'b'
            ? [
                {
                  ...release,
                  releaseId: 'failed-b',
                  sourceSha: 'sha-b',
                  state: 'FAILED',
                  publicationSequence: 2,
                },
                release,
              ]
            : [release],
      });
    if (path.endsWith('/releases/failed-b'))
      return route.fulfill({
        json: {
          ...release,
          releaseId: 'failed-b',
          sourceSha: 'sha-b',
          state: 'FAILED',
          validationSummary: { checks: [{ name: 'test_b', status: 'FAILED' }] },
        },
      });
    if (path.endsWith('/releases/' + version))
      return route.fulfill({
        json: {
          ...release,
          validationSummary: { checks: [{ name: 'definition', status: 'PASSED' }] },
        },
      });
    if (path.endsWith('/catalog'))
      return route.fulfill({
        json: {
          branchId: id,
          releaseId: version,
          page: 1,
          size: 50,
          total: 1,
          resources: [resource],
        },
      });
    if (path.endsWith('/diff'))
      return route.fulfill({
        json: {
          branchId: id,
          releaseId: version,
          baseCommitSha: 'base',
          targetCommitSha: release.sourceSha,
          productionAdvanced: false,
          resourceBaselineAvailable: true,
          truncated: false,
          files: [],
          resources: [],
        },
      });
    if (path.endsWith('/query-options'))
      return route.fulfill({ json: { branchId: id, releaseId: version, options: [] } });
    if (path.endsWith('/queries')) {
      const key = String(body.idempotencyKey);
      // 已受理请求先恢复；版本推进后只有新键应被拒绝。
      if (!queries.has(key) && branch.activeReleaseId !== body.releaseId)
        return route.fulfill({ status: 410, json: { status: 410 } });
      if (!queries.has(key))
        queries.set(key, {
          projectId: 'p',
          branchId: id!,
          releaseId: String(body.releaseId),
          queryId: 'q-' + id,
          state: 'READY',
          columns: [{ name: '销售额', type: 'DECIMAL' }],
          rows: [[id === 'a' ? '62.00' : '31.00']],
        });
      if (loseReceipt) {
        loseReceipt = false;
        return route.abort('failed');
      }
      return route.fulfill({ status: 202, json: queries.get(key) });
    }
    if (path.endsWith('/resources/same')) return route.fulfill({ json: resource });
    if (path.endsWith('/lineage'))
      return route.fulfill({
        json: { branchId: id, releaseId: version, resourceId: 'same', dependencies: [] },
      });
    return route.fulfill({ status: 404, json: { status: 404 } });
  });
  return {
    requests,
    queries,
    loseNextReceipt: () => {
      loseReceipt = true;
    },
    advance: (id: string) => {
      const branch = branches.find((item) => item.branchId === id)!;
      branch.activeReleaseId = 'advanced-' + id;
    },
  };
}
