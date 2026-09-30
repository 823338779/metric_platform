import { expect, it, vi } from 'vitest';
import type { DbtResourcePresentation } from '../../api/types';
import { resolveResourceDataType } from './resourceDataType';

const presentation = (kind = 'DIMENSION', nativeDetails = {}): DbtResourcePresentation => ({
  resourceId: 'selected',
  releaseId: 'release-1',
  kind,
  title: '品牌',
  queryable: true,
  sections: [],
  nativeDetails: { name: 'brand', type: 'categorical', ...nativeDetails },
});
const lineage = {
  releaseId: 'release-1',
  resourceId: 'selected',
  dependencies: [
    {
      upstreamResourceId: 'semantic-model',
      downstreamResourceId: 'selected',
      kind: 'SEMANTIC_DIMENSION',
    },
  ],
};
const client = () => ({
  getDbtResource: vi.fn().mockResolvedValue({
    resourceId: 'semantic-model',
    kind: 'SEMANTIC_MODEL',
    definitionJson: JSON.stringify({
      name: 'products',
      depends_on: { nodes: ['physical-model'] },
    }),
  }),
  getDbtPresentation: vi.fn().mockResolvedValue({
    ...presentation('TABLE'),
    resourceId: 'physical-model',
    sections: [
      {
        title: '物理关系',
        fields: {
          columns: {
            brand: { name: 'brand', type: 'varchar(64)' },
            amount: { name: 'amount', type: 'decimal(18,2)' },
          },
        },
      },
    ],
  }),
});

it('separates categorical from the actual source column type and pins every request to the release', async () => {
  const api = client();
  const result = await resolveResourceDataType(presentation(), lineage, 'shop', api as never);
  expect(result.label).toBe('VARCHAR(64)');
  expect(result.description).toContain('brand');
  expect(api.getDbtResource).toHaveBeenCalledWith('shop', 'semantic-model', 'release-1');
  expect(api.getDbtPresentation).toHaveBeenCalledWith('shop', 'physical-model', 'release-1');
});

it('uses explicit declared result types including precision before attempting lineage', async () => {
  const api = client();
  expect(
    (
      await resolveResourceDataType(
        presentation('METRIC', { data_type: 'decimal(20,4)' }),
        undefined,
        'shop',
        api as never,
      )
    ).label,
  ).toBe('DECIMAL(20,4)');
  expect(api.getDbtResource).not.toHaveBeenCalled();
});

it('does not assume a categorical dimension is a string or use an unrelated column for an expression', async () => {
  const api = client();
  const result = await resolveResourceDataType(
    presentation('DIMENSION', { expr: 'length(brand)' }),
    lineage,
    'shop',
    api as never,
  );
  expect(result.label).toBe('待确认');
  expect(api.getDbtResource).not.toHaveBeenCalled();
});

it('shows aggregate output as numeric without copying input precision to the result', async () => {
  const result = await resolveResourceDataType(
    presentation('METRIC', {
      type: 'simple',
      type_params: { expr: 'amount', metric_aggregation_params: { agg: 'sum' } },
    }),
    lineage,
    'shop',
    client() as never,
  );
  expect(result.label).toBe('NUMBER · 数值');
  expect(result.description).toContain('DECIMAL(18,2)');
  expect(result.description).toContain('结果精度');
});

it('identifies count output as integer without claiming a database-specific width', async () => {
  const result = await resolveResourceDataType(
    presentation('METRIC', {
      type: 'simple',
      type_params: { metric_aggregation_params: { agg: 'count_distinct' } },
    }),
    undefined,
    'shop',
    client() as never,
  );
  expect(result.label).toBe('INTEGER · 整数');
});

it('refuses metadata from a different release or mismatched resource', async () => {
  const api = client();
  api.getDbtPresentation.mockResolvedValue({
    ...presentation('TABLE'),
    releaseId: 'other',
  } as never);
  await expect(
    resolveResourceDataType(presentation(), lineage, 'shop', api as never),
  ).rejects.toThrow();
  api.getDbtResource.mockResolvedValue({
    resourceId: 'other',
    kind: 'SEMANTIC_MODEL',
    definitionJson: '{}',
  });
  await expect(
    resolveResourceDataType(presentation(), lineage, 'shop', api as never),
  ).rejects.toThrow();
});

it('does not select an arbitrary column when there is more than one semantic owner', async () => {
  const result = await resolveResourceDataType(
    presentation(),
    {
      ...lineage,
      dependencies: [
        ...lineage.dependencies,
        { ...lineage.dependencies[0], upstreamResourceId: 'second-model' },
      ],
    },
    'shop',
    client() as never,
  );
  expect(result.label).toBe('待确认');
});
