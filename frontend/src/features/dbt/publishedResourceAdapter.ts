import type {
  BuildCatalogApi,
  PublishedResource,
  PublishedResult,
  PublishedQuery,
} from '../../api/buildCatalogApi';
import type {
  DbtLineage,
  DbtQueryStatus,
  DbtResource,
  DbtResourcePresentation,
  DbtSourceDocument,
} from '../../api/types';
import { RESOURCE_LABELS } from './resourceDisplay';

const QUERY = 'QUERY';
const PREVIEW = 'PREVIEW';
const SEMANTIC_MODEL = 'SEMANTIC_MODEL';
const MISMATCH = '资源或版本身份与请求不一致';
const STATES: Record<string, DbtQueryStatus['status']> = {
  QUEUED: 'PENDING',
  RUNNING: 'RUNNING',
  SUCCEEDED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
  OUTCOME_UNKNOWN: 'OUTCOME_UNKNOWN',
};

export function publishedResult(
  result: PublishedResult,
  mode = result.mode ?? QUERY,
): DbtQueryStatus {
  return {
    queryId: result.queryId,
    buildId: result.buildId,
    targetCommitSha: result.commitSha ?? '',
    mode,
    status: STATES[result.state] ?? 'PENDING',
    columns: result.columns ?? null,
    rows: result.rows ?? null,
    errorCode: result.error?.code ?? null,
    errorMessage: result.error?.message ?? null,
    truncated: result.resultTruncated ?? null,
    durationMs: result.durationMs ?? null,
    sql: result.sql,
  };
}

export function publishedTreeResource(resource: PublishedResource): DbtResource {
  return {
    resourceId: resource.resourceId,
    nativeId: resource.nativeId,
    kind: resource.kind,
    name: resource.name,
    description: resource.description,
    sourcePath: resource.sourcePath,
    queryable: resource.capabilities.includes(QUERY) || resource.capabilities.includes(PREVIEW),
    definitionJson: JSON.stringify(resource.nativeDetails),
  };
}

/** 仅适配展示契约；所有读取和执行仍走 v3，资源与查询身份不转换为名称。 */
export function publishedResourceAdapter(api: BuildCatalogApi) {
  // 适配器生命周期绑定分支/版本；未知受理结果保留原请求，不能重试生成新任务。
  const pendingPreviews = new Map<string, PublishedQuery>();
  const cursors = new Map<string, (string | undefined)[]>();
  const resource = async (project: string, id: string, buildId: string) => {
    const value = await api.resource(buildId, id);
    if (value.buildId !== buildId || value.resourceId !== id) throw new Error(MISMATCH);
    return value;
  };
  const detail = async (
    project: string,
    id: string,
    buildId: string,
    view: 'source' | 'lineage',
  ) => {
    const value = await api.detail(buildId, id, view);
    if (value.buildId !== buildId || value.resourceId !== id) throw new Error(MISMATCH);
    return value;
  };
  return {
    queryParameter: 'metricResourceId' as const,
    async getDbtCatalog(
      project: string,
      buildId: string,
      search = '',
      page = 0,
      _size = 20,
      kind?: string,
    ) {
      const key = JSON.stringify([buildId, search, kind, _size]);
      const known = cursors.get(key) ?? [undefined];
      if (page > 0 && known[page] === undefined) throw new Error('请按顺序加载资源分页');
      const value = await api.catalog(buildId, {
        q: search,
        kind,
        cursor: known[page],
        limit: _size,
      });
      if (value.buildId !== buildId) throw new Error(MISMATCH);
      known[page + 1] = value.nextCursor ?? undefined;
      cursors.set(key, known);
      return {
        buildId: buildId,
        page,
        size: _size,
        total: page * _size + value.items.length + (value.nextCursor ? 1 : 0),
        resources: value.items.map(publishedTreeResource),
      };
    },
    async getDbtResource(project: string, id: string, buildId: string): Promise<DbtResource> {
      const value = await resource(project, id, buildId);
      const mapped = publishedTreeResource(value);
      // 类型追溯只使用发布契约中的平台 ID，不能拿 nativeId 调用资源 API。
      if (value.kind === SEMANTIC_MODEL && typeof value.attributes.modelResourceId === 'string')
        mapped.definitionJson = JSON.stringify({
          ...value.nativeDetails,
          depends_on: { nodes: [value.attributes.modelResourceId] },
        });
      return mapped;
    },
    async getDbtPresentation(
      project: string,
      id: string,
      buildId: string,
    ): Promise<DbtResourcePresentation> {
      const value = await resource(project, id, buildId);
      return {
        buildId: buildId,
        resourceId: id,
        kind: value.kind,
        title: value.name,
        queryable: value.capabilities.includes(QUERY) || value.capabilities.includes(PREVIEW),
        nativeDetails: value.nativeDetails,
        sections: [
          {
            title: RESOURCE_LABELS[value.kind] ?? value.kind,
            fields: {
              ...value.attributes,
              description: value.description,
              ...(value.displayName !== value.name ? { label: value.displayName } : {}),
            },
          },
        ],
      };
    },
    async getDbtLineage(project: string, id: string, buildId: string): Promise<DbtLineage> {
      const value = await detail(project, id, buildId, 'lineage');
      if (
        !Array.isArray(value.dependencies) ||
        value.dependencies.some(
          (edge) =>
            !edge ||
            typeof edge.upstreamResourceId !== 'string' ||
            typeof edge.downstreamResourceId !== 'string' ||
            typeof edge.kind !== 'string',
        )
      )
        throw new Error(MISMATCH);
      return { buildId: buildId, resourceId: id, dependencies: value.dependencies };
    },
    async getDbtSource(project: string, id: string, buildId: string): Promise<DbtSourceDocument> {
      const value = await detail(project, id, buildId, 'source');
      if (typeof value.content !== 'string' || typeof value.path !== 'string')
        throw new Error(MISMATCH);
      return {
        buildId: buildId,
        sourcePath: value.path,
        buildSourceSha: '',
        content: value.content,
      };
    },
    async previewDbtDataset(project: string, id: string, buildId: string, limit: number) {
      const signature = JSON.stringify([project, id, buildId, limit]);
      const body = pendingPreviews.get(signature) ?? {
        idempotencyKey: crypto.randomUUID(),
        mode: PREVIEW,
        datasetResourceId: id,
        metricResourceIds: [],
        limit,
      };
      pendingPreviews.set(signature, body);
      const value = await api.submit(buildId, body);
      if (value.buildId !== buildId) throw new Error(MISMATCH);
      pendingPreviews.delete(signature);
      return publishedResult(value, PREVIEW);
    },
    async getDbtQuery(project: string, query: string) {
      const status = await api.query(query);
      const value =
        status.state === 'SUCCEEDED' && status.resultAvailable ? await api.results(query) : status;
      if (value.queryId !== query || value.buildId !== status.buildId) throw new Error(MISMATCH);
      return publishedResult(value, PREVIEW);
    },
  };
}
