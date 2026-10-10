import { describe, expect, it, vi } from 'vitest';
import { createBuildCatalogApi } from './buildCatalogApi';

const B1 = '11111111-1111-4111-8111-111111111111';
const TASK = '22222222-2222-4222-8222-222222222222';
describe('唯一构建协议', () => {
  it('查询行数1000与结果单页200独立，按nextOffset继续且保留精度和时间', async () => {
    const firstRows = Array.from({ length: 200 }, () => ['9007199254740993.01']);
    const lastRows = [['9007199254740994.02']];
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ queryId: TASK, buildId: B1 })))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            queryId: TASK,
            buildId: B1,
            rows: firstRows,
            returnedRows: 200,
            nextOffset: 200,
            businessTimezone: 'Asia/Shanghai',
          }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            queryId: TASK,
            buildId: B1,
            rows: lastRows,
            returnedRows: 1,
            nextOffset: null,
            businessTimezone: 'Asia/Shanghai',
          }),
        ),
      );
    const api = createBuildCatalogApi(fetcher);
    await api.submit(B1, {
      idempotencyKey: 'query-page-test',
      mode: 'QUERY',
      metricResourceIds: ['metric'],
      limit: 1000,
      startTime: '2026-10-10T00:00:00+08:00',
    });
    const first = await api.results(TASK);
    const last = await api.results(TASK, first.nextOffset!);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      `/api/v3/dbt/builds/${B1}/queries`,
      `/api/v3/dbt/queries/${TASK}/results?offset=0&limit=200`,
      `/api/v3/dbt/queries/${TASK}/results?offset=200&limit=200`,
    ]);
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toMatchObject({
      limit: 1000,
      startTime: '2026-10-10T00:00:00+08:00',
    });
    expect([...first.rows!, ...last.rows!]).toEqual([...firstRows, ...lastRows]);
    expect(last.businessTimezone).toBe('Asia/Shanghai');
    expect(last.nextOffset).toBeNull();
  });

  it('removedApiHasNoFallback', async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 'BUILD_NOT_FOUND', detail: '不存在' }), {
        status: 404,
      }),
    );
    await expect(createBuildCatalogApi(fetcher).build(B1)).rejects.toMatchObject({
      problem: { code: 'BUILD_NOT_FOUND' },
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][0]).toBe(`/api/v3/dbt/builds/${B1}`);
  });
  it('目录资源与异步选项固定 buildId，精度与时间原样传递', async () => {
    const fetcher = vi.fn().mockImplementation(
      async () =>
        new Response(
          JSON.stringify({
            buildId: B1,
            items: [],
            nextCursor: null,
            optionsTaskId: TASK,
            state: 'QUEUED',
          }),
        ),
    );
    const api = createBuildCatalogApi(fetcher);
    await api.catalog(B1, { cursor: 'opaque', limit: 50 });
    await api.resource(B1, 'metric/a');
    await api.options(B1, { idempotencyKey: 'key', metricResourceIds: ['m'] });
    await api.getOptions(TASK);
    const input = {
      idempotencyKey: 'q-key',
      mode: 'QUERY' as const,
      metricResourceIds: ['m'],
      filters: [{ optionId: 'o', operator: '=', value: '9007199254740993' }],
      startTime: '2026-10-10T00:00:00+08:00',
    };
    await api.submit(B1, input);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      `/api/v3/dbt/builds/${B1}/catalog?limit=50&cursor=opaque`,
      `/api/v3/dbt/builds/${B1}/resources/metric%2Fa`,
      `/api/v3/dbt/builds/${B1}/query-options`,
      `/api/v3/dbt/query-options/${TASK}`,
      `/api/v3/dbt/builds/${B1}/queries`,
    ]);
    expect(JSON.parse(fetcher.mock.calls[4][1].body)).toEqual(input);
    expect(JSON.parse(fetcher.mock.calls[4][1].body)).not.toHaveProperty('buildId');
  });
  it('相同分支名由仓库和环境隔离，不使用服务项目身份', async () => {
    const fetcher = vi.fn().mockImplementation(async () => new Response('{}'));
    const api = createBuildCatalogApi(fetcher);
    await api.current({
      repository: 'repo/a',
      branchName: 'feature/shared',
      environment: 'PREVIEW',
    });
    await api.current({
      repository: 'repo/b',
      branchName: 'feature/shared',
      environment: 'PREVIEW',
    });
    expect(fetcher.mock.calls[0][0]).toContain('repository=repo%2Fa');
    expect(fetcher.mock.calls[1][0]).toContain('repository=repo%2Fb');
  });
});

it.each(['QUERY', 'EXPLAIN', 'PREVIEW', 'DIMENSION_VALUES'] as const)(
  '四种查询模式 %s 都使用固定构建与类型化请求',
  async (mode) => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ queryId: TASK, buildId: B1, state: 'QUEUED' })),
      );
    const body = {
      idempotencyKey: 'intent-key',
      mode,
      metricResourceIds: mode === 'PREVIEW' ? [] : ['metric'],
      ...(mode === 'PREVIEW' ? { datasetResourceId: 'dataset' } : {}),
      ...(mode === 'DIMENSION_VALUES' ? { dimensionOptionId: 'opaque-option' } : {}),
    };
    await createBuildCatalogApi(fetcher).submit(B1, body);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][0]).toBe(`/api/v3/dbt/builds/${B1}/queries`);
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual(body);
    expect(body).not.toHaveProperty('sql');
  },
);
