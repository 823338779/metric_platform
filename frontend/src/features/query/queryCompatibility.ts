import type { QueryConnectivityResponse, SemanticSearchResult } from '../../api/types';

export type CompatibilityReason =
  | 'SELECT_METRIC_FIRST'
  | 'DIFFERENT_DATA_SOURCE'
  | 'RELATIONSHIP_PATH_NOT_FOUND'
  | 'AMBIGUOUS_RELATIONSHIP_PATH';

export type ObjectCompatibility =
  | { state: 'ADDABLE' }
  | { state: 'SELECTED' }
  | { state: 'INCOMPATIBLE'; reason: CompatibilityReason };

const stateOrder: Record<ObjectCompatibility['state'], number> = {
  ADDABLE: 0,
  SELECTED: 1,
  INCOMPATIBLE: 2,
};

export function classifySemanticObjects(
  objects: SemanticSearchResult[],
  metricIds: string[],
  dimensionIds: string[],
  connectivity: QueryConnectivityResponse,
): Record<string, ObjectCompatibility> {
  const objectsById = new Map(objects.map((value) => [value.id, value]));
  const dataSourceByDataset = new Map(
    connectivity.datasets.map((value) => [value.datasetId, value.dataSourceId]),
  );
  const paths = new Map(
    connectivity.paths.map((value) => [
      pathKey(value.sourceDatasetId, value.targetDatasetId),
      value,
    ]),
  );
  const selected = new Set([...metricIds, ...dimensionIds]);
  const metrics = metricIds.flatMap((id) => {
    const value = objectsById.get(id);
    return value ? [value] : [];
  });
  const dimensions = dimensionIds.flatMap((id) => {
    const value = objectsById.get(id);
    return value ? [value] : [];
  });
  const selectedDataSource = [...metrics, ...dimensions]
    .map((value) => dataSourceByDataset.get(value.datasetId))
    .find((value): value is string => Boolean(value));

  return Object.fromEntries(
    objects.map((value) => {
      if (selected.has(value.id)) return [value.id, { state: 'SELECTED' }];
      const dataSourceId = dataSourceByDataset.get(value.datasetId);
      if (selectedDataSource && dataSourceId !== selectedDataSource) {
        return [value.id, { state: 'INCOMPATIBLE', reason: 'DIFFERENT_DATA_SOURCE' }];
      }
      if (value.type === 'METRIC') {
        return [value.id, compatibilityForTargets(value.datasetId, dimensions, paths)];
      }
      if (value.type === 'DIMENSION') {
        if (!metrics.length) {
          return [value.id, { state: 'INCOMPATIBLE', reason: 'SELECT_METRIC_FIRST' }];
        }
        return [value.id, compatibilityForSources(metrics, value.datasetId, paths)];
      }
      return [value.id, { state: 'INCOMPATIBLE', reason: 'RELATIONSHIP_PATH_NOT_FOUND' }];
    }),
  );
}

export function sortSemanticObjects(
  objects: SemanticSearchResult[],
  compatibility: Record<string, ObjectCompatibility>,
  searchActive: boolean,
): SemanticSearchResult[] {
  return objects
    .map((value, index) => ({ value, index }))
    .sort((left, right) => {
      const stateDifference =
        stateOrder[compatibility[left.value.id]?.state ?? 'INCOMPATIBLE'] -
        stateOrder[compatibility[right.value.id]?.state ?? 'INCOMPATIBLE'];
      if (stateDifference) return stateDifference;
      if (searchActive) return left.index - right.index;
      return left.value.displayName.localeCompare(right.value.displayName, 'zh-CN');
    })
    .map(({ value }) => value);
}

function compatibilityForTargets(
  sourceDatasetId: string,
  targets: SemanticSearchResult[],
  paths: Map<string, QueryConnectivityResponse['paths'][number]>,
): ObjectCompatibility {
  for (const target of targets) {
    const reason = pathReason(sourceDatasetId, target.datasetId, paths);
    if (reason) return { state: 'INCOMPATIBLE', reason };
  }
  return { state: 'ADDABLE' };
}

function compatibilityForSources(
  sources: SemanticSearchResult[],
  targetDatasetId: string,
  paths: Map<string, QueryConnectivityResponse['paths'][number]>,
): ObjectCompatibility {
  for (const source of sources) {
    const reason = pathReason(source.datasetId, targetDatasetId, paths);
    if (reason) return { state: 'INCOMPATIBLE', reason };
  }
  return { state: 'ADDABLE' };
}

function pathReason(
  sourceDatasetId: string,
  targetDatasetId: string,
  paths: Map<string, QueryConnectivityResponse['paths'][number]>,
): CompatibilityReason | undefined {
  if (sourceDatasetId === targetDatasetId) return undefined;
  const path = paths.get(pathKey(sourceDatasetId, targetDatasetId));
  if (!path) return 'RELATIONSHIP_PATH_NOT_FOUND';
  return path.status === 'AMBIGUOUS_PATH' ? 'AMBIGUOUS_RELATIONSHIP_PATH' : undefined;
}

function pathKey(sourceDatasetId: string, targetDatasetId: string) {
  return `${sourceDatasetId}\u0000${targetDatasetId}`;
}
