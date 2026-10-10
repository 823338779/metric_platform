import { expect, it } from 'vitest';
import { hasSerializedNode, restoreArtifactPresentation } from './resourcePresentation';

const brokenNode = {
  nodeType: 'STRING',
  valueNode: true,
  containerNode: false,
  missingNode: false,
  textual: true,
  bigDecimal: false,
};
const presentation = {
  buildId: 'release-1',
  resourceId: 'brand',
  kind: 'DIMENSION',
  title: 'brand',
  queryable: false,
  sections: [{ title: '维度', fields: { type: brokenNode, description: brokenNode } }],
  nativeDetails: brokenNode,
};
const resource = {
  resourceId: 'brand',
  nativeId: 'brand',
  kind: 'DIMENSION',
  name: 'brand',
  queryable: false,
  definitionJson: JSON.stringify({ name: 'brand', type: 'categorical', description: '商品品牌' }),
};

it('restores real scalar values from the original definition without guessing from node flags', () => {
  const result = restoreArtifactPresentation(presentation, resource);
  expect(result.sections[0].fields).toEqual({ type: 'categorical', description: '商品品牌' });
  expect(result.nativeDetails).toEqual(JSON.parse(resource.definitionJson));
  expect(hasSerializedNode(result)).toBe(false);
});

it('keeps physical catalog columns distinct from model columns and preserves false, zero and null', () => {
  const result = restoreArtifactPresentation(
    {
      ...presentation,
      kind: 'TABLE',
      sections: [
        { title: '物理关系', fields: { columns: brokenNode } },
        { title: '模型', fields: { columns: brokenNode } },
      ],
    },
    {
      ...resource,
      kind: 'TABLE',
      definitionJson: JSON.stringify({
        columns: { amount: { description: '金额', enabled: false, precision: 0, label: null } },
        _platformCatalog: { columns: [{ name: 'amount', type: 'numeric' }] },
      }),
    },
  );
  expect(result.sections[0].fields.columns).toEqual([{ name: 'amount', type: 'numeric' }]);
  expect(result.sections[1].fields.columns).toEqual({
    amount: { description: '金额', enabled: false, precision: 0, label: null },
  });
  expect(result.nativeDetails).not.toHaveProperty('_platformCatalog');
});

it('does not mistake business objects containing type-related fields for broken serialization', () => {
  expect(hasSerializedNode({ nodeType: 'STRING', textual: true })).toBe(false);
  expect(hasSerializedNode({ fields: [brokenNode] })).toBe(true);
});

it('rejects missing original values, invalid JSON and mismatched resources instead of inventing values', () => {
  expect(() =>
    restoreArtifactPresentation(presentation, { ...resource, definitionJson: '{}' }),
  ).toThrow();
  expect(() =>
    restoreArtifactPresentation(presentation, { ...resource, definitionJson: 'null' }),
  ).toThrow();
  expect(() =>
    restoreArtifactPresentation(presentation, { ...resource, definitionJson: 'invalid' }),
  ).toThrow();
  expect(() =>
    restoreArtifactPresentation(presentation, { ...resource, resourceId: 'other' }),
  ).toThrow();
});
