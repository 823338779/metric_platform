import { ApiProblem } from '../../api/httpClient';
import { describe, expect, it, vi } from 'vitest';
import { publishedResourceAdapter, publishedResult } from './publishedResourceAdapter';
import type { BuildCatalogApi } from '../../api/buildCatalogApi';

const RELEASE = 'release-a';
const PROJECT = 'project';
const previewResource = async (_build: string, id: string) => ({
  buildId: RELEASE,
  resourceId: id,
  resource: { name: 'orders', resource_type: 'model', fqn: ['shop', 'orders'] },
  source: null,
});
const BASE = {
  buildId: RELEASE,
  nativeId: 'native',
  name: 'brand',
  displayName: '品牌',
  description: null,
  sourcePath: null,
  capabilities: [],
  nativeDetails: {},
  attributes: {},
};

describe('published presentation contract', () => {
  it('预览响应丢失后重新执行仍固定原构建，且不发送幂等键', async () => {
    const submit = vi
      .fn()
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValue({ buildId: RELEASE, mode: 'PREVIEW', rows: [['value']], columns: [] });
    const adapter = publishedResourceAdapter({
      submit,
      resource: previewResource,
    } as unknown as BuildCatalogApi);
    await expect(adapter.previewDbtDataset(PROJECT, 'dataset', RELEASE, 10)).rejects.toThrow(
      'lost',
    );
    const result = await adapter.previewDbtDataset(PROJECT, 'dataset', RELEASE, 10);
    expect(result.status).toBe('COMPLETED');
    expect(result.queryId).toBeUndefined();
    expect(submit.mock.calls[1]).toEqual(submit.mock.calls[0]);
    expect(submit.mock.calls[1][1]).not.toHaveProperty('idempotencyKey');
    expect(submit.mock.calls[1][0]).toBe(RELEASE);
  });

  it('原生模型列类型保留在展示契约中，并使用原生资源身份', async () => {
    const id = 'model.shop.orders';
    const resource = vi.fn().mockResolvedValue({
      buildId: RELEASE,
      resourceId: id,
      resource: {
        unique_id: id,
        resource_type: 'model',
        name: 'orders',
        config: { materialized: 'table' },
        columns: { brand: { data_type: 'VARCHAR(64)' } },
      },
      source: null,
    });
    const adapter = publishedResourceAdapter({ resource } as unknown as BuildCatalogApi);
    const presentation = await adapter.getDbtPresentation(PROJECT, id, RELEASE);
    expect(presentation.nativeDetails.columns).toEqual({ brand: { data_type: 'VARCHAR(64)' } });
    expect(resource).toHaveBeenCalledWith(RELEASE, id);
  });

  it('rejects another release in resource details and lineage', async () => {
    const api = {
      resource: vi.fn().mockResolvedValue({ ...BASE, resourceId: 'x', buildId: 'other' }),
      lineage: vi.fn().mockResolvedValue({ buildId: 'other', resourceId: 'x', dependencies: [] }),
    };
    const adapter = publishedResourceAdapter(api as unknown as BuildCatalogApi);
    await expect(adapter.getDbtPresentation(PROJECT, 'x', RELEASE)).rejects.toThrow('身份');
    await expect(adapter.getDbtLineage(PROJECT, 'x', RELEASE)).rejects.toThrow('身份');
  });

  it('keeps raw values and presents completed results without inventing query identity', () => {
    const result = publishedResult({
      buildId: RELEASE,
      mode: 'QUERY',
      commitSha: null,
      businessTimezone: 'Asia/Shanghai',
      normalizedTimeRange: { startTime: null, endTime: null },
      resultTruncated: false,
      rows: [['9007199254740993.01']],
      columns: [{ name: 'amount', type: 'DECIMAL' }],
    });
    expect(result.status).toBe('COMPLETED');
    expect(result.queryId).toBeUndefined();
    expect(result.rows).toEqual([['9007199254740993.01']]);
  });
});

it.each(['COMMAND_FAILED', 'INVALID_QUERY', 'RESULT_INVALID', 'RESULT_TOO_LARGE'])(
  '预览明确终态失败 %s 后下一次调用保持固定构建且不携带幂等键',
  async (code) => {
    const submit = vi
      .fn()
      .mockRejectedValueOnce(
        new ApiProblem({
          status: 503,
          title: 'Failure',
          detail: 'failed',
          code,
          retryable: false,
          buildId: RELEASE,
        }),
      )
      .mockResolvedValue({ buildId: RELEASE, queryId: 'new-query', state: 'SUCCEEDED' });
    const adapter = publishedResourceAdapter({
      submit,
      resource: previewResource,
    } as unknown as BuildCatalogApi);
    await expect(adapter.previewDbtDataset(PROJECT, 'dataset', RELEASE, 10)).rejects.toThrow(
      'failed',
    );
    await adapter.previewDbtDataset(PROJECT, 'dataset', RELEASE, 10);
    expect(submit.mock.calls[1][1]).not.toHaveProperty('idempotencyKey');
    expect(submit.mock.calls[1]).toEqual(submit.mock.calls[0]);
  },
);
it.each([
  'COMMAND_TIMEOUT',
  'TASK_TIMEOUT',
  'EXECUTION_OUTCOME_UNKNOWN',
  'WORKER_FAILED',
  'LEASE_LOST',
  'IDEMPOTENCY_CONFLICT',
  'RESULT_NOT_READY',
])('预览不确定或冲突响应 %s 手动再次执行仍保留相同输入', async (code) => {
  const submit = vi
    .fn()
    .mockRejectedValueOnce(
      new ApiProblem({
        status: 503,
        title: 'Failure',
        detail: 'uncertain',
        code,
        retryable: false,
        buildId: RELEASE,
      }),
    )
    .mockResolvedValue({ buildId: RELEASE, queryId: 'original-query', state: 'SUCCEEDED' });
  const adapter = publishedResourceAdapter({
    submit,
    resource: previewResource,
  } as unknown as BuildCatalogApi);
  await expect(adapter.previewDbtDataset(PROJECT, 'dataset', RELEASE, 10)).rejects.toThrow(
    'uncertain',
  );
  await adapter.previewDbtDataset(PROJECT, 'dataset', RELEASE, 10);
  expect(submit.mock.calls[1][1]).toEqual(submit.mock.calls[0][1]);
});
