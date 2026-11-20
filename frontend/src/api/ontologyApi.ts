import { createHttpClient } from './httpClient';

export type Property = {
  id: string;
  name: string;
  columnName: string;
  declaredType: string | null;
};
export type ObjectType = {
  id: string;
  name: string;
  description: string | null;
  datasetAssetId: string;
  primaryPropertyId: string;
  properties: Property[];
};
export type Cardinality = 'ONE_TO_ONE' | 'MANY_TO_ONE' | 'ONE_TO_MANY';
export type Relation = {
  id: string;
  name: string;
  description: string | null;
  sourceObjectId: string;
  sourcePropertyId: string;
  targetObjectId: string;
  targetPropertyId: string;
  cardinality: Cardinality;
};
export type Finding = {
  kind: 'ENVIRONMENT' | 'OBJECT' | 'PROPERTY' | 'RELATION';
  id: string | null;
  status: 'COMPATIBLE' | 'UNVERIFIED' | 'NEEDS_REPAIR';
  reason: string;
};
export type Topology = {
  revision: number;
  objects: ObjectType[];
  relations: Relation[];
  bindingReport: { buildId: string | null; revision: number; findings: Finding[] };
};
export type Datasets = { buildId: string | null; datasets: { assetId: string; name: string }[] };
export type Columns = {
  buildId: string;
  assetId: string;
  columns: { name: string; declaredType: string | null }[];
};
type Saved = { id: string; revision: number };
const request = createHttpClient(undefined, '/api/dbt/environments');
const root = (environmentId: string) => '/' + encodeURIComponent(environmentId) + '/ontology';

function write(
  environmentId: string,
  collection: string,
  value: ObjectType | Relation,
  create: boolean,
  body: unknown,
) {
  const path =
    root(environmentId) + '/' + collection + (create ? '' : '/' + encodeURIComponent(value.id));
  return request<Saved>(path, { method: create ? 'POST' : 'PUT', body: JSON.stringify(body) });
}

export const ontologyApi = {
  get: (environmentId: string) => request<Topology>(root(environmentId)),
  datasets: (environmentId: string) => request<Datasets>(root(environmentId) + '/datasets'),
  columns: (environmentId: string, assetId: string, buildId: string) =>
    request<Columns>(
      root(environmentId) +
        '/datasets/' +
        encodeURIComponent(assetId) +
        '/columns?' +
        new URLSearchParams({ buildId }),
    ),
  saveObject: (
    environmentId: string,
    expectedRevision: number,
    sourceBuildId: string | null,
    object: ObjectType,
    create: boolean,
  ) => write(environmentId, 'objects', object, create, { expectedRevision, sourceBuildId, object }),
  saveRelation: (
    environmentId: string,
    expectedRevision: number,
    sourceBuildId: string | null,
    relation: Relation,
    create: boolean,
  ) =>
    write(environmentId, 'relations', relation, create, {
      expectedRevision,
      sourceBuildId,
      relation,
    }),
  remove: (
    environmentId: string,
    collection: 'objects' | 'relations',
    id: string,
    expectedRevision: number,
  ) =>
    request<Saved>(
      root(environmentId) +
        '/' +
        collection +
        '/' +
        encodeURIComponent(id) +
        '?' +
        new URLSearchParams({ expectedRevision: String(expectedRevision) }),
      { method: 'DELETE' },
    ),
};
