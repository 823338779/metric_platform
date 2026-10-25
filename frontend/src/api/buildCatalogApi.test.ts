import { describe, expect, it, vi } from 'vitest';
import { createBuildCatalogApi, type PublishedQuery } from './buildCatalogApi';

const B1 = '11111111-1111-4111-8111-111111111111';
const TASK = '22222222-2222-4222-8222-222222222222';
describe('唯一构建协议', () => {
  it('同步返回完整有界结果，保留精度和时间且不发送幂等键或读取分页', async () => {
    const rows = Array.from({ length: 201 }, () => ['9007199254740993.01']);
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          buildId: B1,
          rows,
          businessTimezone: 'Asia/Shanghai',
          resultTruncated: false,
        }),
      ),
    );
    const result = await createBuildCatalogApi(fetcher).submit(B1, {
      mode: 'QUERY',
      metrics: ['metric'],
      limit: 1000,
      startTime: '2026-10-10T00:00:00+08:00',
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][0]).toBe(`/api/dbt/builds/${B1}/queries`);
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({
      mode: 'QUERY',
      metrics: ['metric'],
      limit: 1000,
      startTime: '2026-10-10T00:00:00+08:00',
    });
    expect(result.rows).toEqual(rows);
    expect(result.businessTimezone).toBe('Asia/Shanghai');
    expect(result).not.toHaveProperty('queryId');
    expect(createBuildCatalogApi(fetcher)).not.toHaveProperty('results');
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
    expect(fetcher.mock.calls[0][0]).toBe(`/api/dbt/builds/${B1}`);
  });
  it('目录资源与同步选项固定 buildId，精度与时间原样传递', async () => {
    const fetcher = vi.fn().mockImplementation(
      async () =>
        new Response(
          JSON.stringify({
            buildId: B1,
            items: [],
            nextCursor: null,
            dimensions: [],
          }),
        ),
    );
    const api = createBuildCatalogApi(fetcher);
    await api.catalog(B1);
    await api.resource(B1, 'metric/a');
    await api.availableDimensions(B1, { metrics: ['m'] });
    const input = {
      mode: 'QUERY' as const,
      metrics: ['m'],
      filters: [{ optionId: 'o', operator: '=', value: '9007199254740993' }],
      startTime: '2026-10-10T00:00:00+08:00',
    };
    await api.submit(B1, input);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      `/api/dbt/builds/${B1}/catalog`,
      `/api/dbt/builds/${B1}/resources/metric%2Fa`,
      `/api/dbt/builds/${B1}/available-dimensions`,
      `/api/dbt/builds/${B1}/queries`,
    ]);
    expect(JSON.parse(fetcher.mock.calls[3][1].body)).toEqual(input);
    expect(JSON.parse(fetcher.mock.calls[3][1].body)).not.toHaveProperty('buildId');
  });
  it('相同分支名由仓库和环境隔离，不使用服务项目身份', async () => {
    const fetcher = vi.fn().mockImplementation(async () => new Response('{}'));
    const api = createBuildCatalogApi(fetcher);
    await api.current({
      repository: 'repo/a',
      branchName: 'feature/shared',
      environmentId: '12345678-1234-4234-8234-000000000001',
    });
    await api.current({
      repository: 'repo/b',
      branchName: 'feature/shared',
      environmentId: '12345678-1234-4234-8234-000000000001',
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
        new Response(JSON.stringify({ queryId: TASK, buildId: B1, state: 'SUCCEEDED' })),
      );
    const body: PublishedQuery =
      mode === 'PREVIEW'
        ? { mode, selector: 'dataset' }
        : mode === 'DIMENSION_VALUES'
          ? { mode, metrics: ['metric'], dimensionOptionId: 'opaque-option' }
          : { mode, metrics: ['metric'] };
    await createBuildCatalogApi(fetcher).submit(B1, body);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][0]).toBe(`/api/dbt/builds/${B1}/queries`);
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual(body);
    expect(body).not.toHaveProperty('sql');
  },
);
