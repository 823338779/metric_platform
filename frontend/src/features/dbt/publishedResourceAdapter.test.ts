import { describe, expect, it, vi } from 'vitest';
import { publishedResourceAdapter, publishedResult } from './publishedResourceAdapter';
import { resolveResourceDataType } from './resourceDataType';
import type { PublishedCatalogApi } from '../../api/publishedCatalogApi';

const RELEASE = 'release-a';
const PROJECT = 'project';
const BASE = {
  releaseId: RELEASE,
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
  it('follows platform identities for a directly mapped column without calling a native ID', async () => {
    const resources = {
      dimension: {
        ...BASE,
        resourceId: 'dimension',
        kind: 'DIMENSION',
        attributes: { dimensionType: 'categorical', expression: 'brand' },
        nativeDetails: { name: 'brand', type: 'categorical' },
      },
      dataset: {
        ...BASE,
        resourceId: 'dataset',
        kind: 'SEMANTIC_MODEL',
        attributes: { modelResourceId: 'physical' },
        nativeDetails: { depends_on: { nodes: ['native.model.id'] } },
      },
      physical: {
        ...BASE,
        resourceId: 'physical',
        kind: 'TABLE',
        attributes: { columns: { brand: { type: 'VARCHAR(64)' } } },
      },
    };
    const resource = vi.fn(async (_project, _release, id: keyof typeof resources) => resources[id]);
    const adapter = publishedResourceAdapter({ resource } as unknown as PublishedCatalogApi);
    const presentation = await adapter.getDbtPresentation(PROJECT, 'dimension', RELEASE);
    const value = await resolveResourceDataType(
      presentation,
      {
        releaseId: RELEASE,
        resourceId: 'dimension',
        dependencies: [
          { upstreamResourceId: 'dataset', downstreamResourceId: 'dimension', kind: 'dimension' },
        ],
      },
      PROJECT,
      adapter,
    );
    expect(value.label).toBe('VARCHAR(64)');
    expect(resource).toHaveBeenCalledWith(PROJECT, RELEASE, 'physical');
    expect(resource).not.toHaveBeenCalledWith(PROJECT, RELEASE, 'native.model.id');
  });

  it('rejects another release in resource details and lineage', async () => {
    const api = {
      resource: vi.fn().mockResolvedValue({ ...BASE, resourceId: 'x', releaseId: 'other' }),
      detail: vi.fn().mockResolvedValue({ releaseId: 'other', resourceId: 'x', dependencies: [] }),
    };
    const adapter = publishedResourceAdapter(api as unknown as PublishedCatalogApi);
    await expect(adapter.getDbtPresentation(PROJECT, 'x', RELEASE)).rejects.toThrow('身份');
    await expect(adapter.getDbtLineage(PROJECT, 'x', RELEASE)).rejects.toThrow('身份');
  });

  it('keeps raw result values and maps query states without changing query identity', () => {
    const result = publishedResult({
      queryId: 'q',
      projectId: PROJECT,
      releaseId: RELEASE,
      state: 'READY',
      rows: [['9007199254740993.01']],
      columns: [{ name: 'amount', type: 'DECIMAL' }],
    });
    expect(result.status).toBe('COMPLETED');
    expect(result.queryId).toBe('q');
    expect(result.rows).toEqual([['9007199254740993.01']]);
  });
});
