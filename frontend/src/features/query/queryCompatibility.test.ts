import { describe, expect, it } from 'vitest';
import type { QueryConnectivityResponse, SemanticSearchResult } from '../../api/types';
import { classifySemanticObjects, sortSemanticObjects } from './queryCompatibility';

const sourceA = 'source-a';
const sourceB = 'source-b';
const connectivity: QueryConnectivityResponse = {
  catalogVersion: 'catalog-1',
  datasets: [
    { datasetId: 'orders', dataSourceId: sourceA },
    { datasetId: 'customers', dataSourceId: sourceA },
    { datasetId: 'regions', dataSourceId: sourceA },
    { datasetId: 'ambiguous', dataSourceId: sourceA },
    { datasetId: 'other', dataSourceId: sourceB },
  ],
  paths: [
    {
      sourceDatasetId: 'orders',
      targetDatasetId: 'customers',
      status: 'UNIQUE_PATH',
      relationshipIds: ['orders-customers'],
    },
    {
      sourceDatasetId: 'orders',
      targetDatasetId: 'ambiguous',
      status: 'AMBIGUOUS_PATH',
      relationshipIds: [],
    },
    {
      sourceDatasetId: 'regions',
      targetDatasetId: 'customers',
      status: 'UNIQUE_PATH',
      relationshipIds: ['regions-customers'],
    },
  ],
};

const objects = [
  semantic('metricOrders', 'METRIC', 'orders', '订单金额'),
  semantic('metricRegions', 'METRIC', 'regions', '区域金额'),
  semantic('metricOtherSource', 'METRIC', 'other', '其他金额'),
  semantic('dimensionCustomer', 'DIMENSION', 'customers', '客户'),
  semantic('dimensionMissing', 'DIMENSION', 'regions', '区域'),
  semantic('dimensionAmbiguous', 'DIMENSION', 'ambiguous', '歧义维度'),
];

describe('query compatibility', () => {
  it('allows metrics and requires a metric before dimensions for an empty selection', () => {
    const states = classifySemanticObjects(objects, [], [], connectivity);

    expect(states.metricOrders.state).toBe('ADDABLE');
    expect(states.dimensionCustomer).toEqual({
      state: 'INCOMPATIBLE',
      reason: 'SELECT_METRIC_FIRST',
    });
  });

  it('requires every selected metric dataset to uniquely reach a candidate dimension', () => {
    const states = classifySemanticObjects(
      objects,
      ['metricOrders', 'metricRegions'],
      [],
      connectivity,
    );

    expect(states.dimensionCustomer.state).toBe('ADDABLE');
    expect(states.dimensionMissing).toEqual({
      state: 'INCOMPATIBLE',
      reason: 'RELATIONSHIP_PATH_NOT_FOUND',
    });
    expect(states.dimensionAmbiguous).toEqual({
      state: 'INCOMPATIBLE',
      reason: 'AMBIGUOUS_RELATIONSHIP_PATH',
    });
  });

  it('rejects a metric from another data source', () => {
    const states = classifySemanticObjects(objects, ['metricOrders'], [], connectivity);

    expect(states.metricOtherSource).toEqual({
      state: 'INCOMPATIBLE',
      reason: 'DIFFERENT_DATA_SOURCE',
    });
  });

  it('uses retained dimensions to constrain the next metric', () => {
    const states = classifySemanticObjects(objects, [], ['dimensionCustomer'], connectivity);

    expect(states.metricOrders.state).toBe('ADDABLE');
    expect(states.metricRegions.state).toBe('ADDABLE');
    expect(states.metricOtherSource).toEqual({
      state: 'INCOMPATIBLE',
      reason: 'DIFFERENT_DATA_SOURCE',
    });
    expect(states.dimensionCustomer.state).toBe('SELECTED');
  });

  it('sorts addable then selected then incompatible while preserving search relevance', () => {
    const ordered = [objects[1], objects[0], objects[2]];
    const states = {
      metricRegions: { state: 'ADDABLE' as const },
      metricOrders: { state: 'SELECTED' as const },
      metricOtherSource: {
        state: 'INCOMPATIBLE' as const,
        reason: 'DIFFERENT_DATA_SOURCE' as const,
      },
    };

    expect(sortSemanticObjects(ordered, states, true).map((value) => value.id)).toEqual([
      'metricRegions',
      'metricOrders',
      'metricOtherSource',
    ]);
  });
});

function semantic(
  id: string,
  type: 'METRIC' | 'DIMENSION',
  datasetId: string,
  displayName: string,
): SemanticSearchResult {
  return {
    id,
    type,
    datasetId,
    displayName,
    name: id,
    aliases: [],
    score: 1,
    matchReasons: [],
    version: 1,
    summary: {},
  };
}
