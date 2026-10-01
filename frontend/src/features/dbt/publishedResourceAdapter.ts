import type {
  PublishedCatalogApi,
  PublishedResource,
  PublishedResult,
} from '../../api/publishedCatalogApi';
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
  READY: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
};

export function publishedResult(
  result: PublishedResult,
  mode = result.mode ?? QUERY,
): DbtQueryStatus {
  return {
    queryId: result.queryId,
    releaseId: result.releaseId,
    targetCommitSha: result.targetCommitSha ?? '',
    mode,
    status: STATES[result.state] ?? 'PENDING',
    columns: result.columns ?? null,
    rows: result.rows ?? null,
    errorCode: result.errorCode ?? null,
    truncated: result.truncated ?? null,
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

/** 仅适配展示契约；所有读取和执行仍走 v2，资源与查询身份不转换为名称。 */
export function publishedResourceAdapter(api: PublishedCatalogApi) {
  const resource = async (project: string, id: string, release: string) => {
    const value = await api.resource(project, release, id);
    if (value.releaseId !== release || value.resourceId !== id) throw new Error(MISMATCH);
    return value;
  };
  const detail = async (
    project: string,
    id: string,
    release: string,
    view: 'source' | 'lineage',
  ) => {
    const value = await api.detail(project, release, id, view);
    if (value.releaseId !== release || value.resourceId !== id) throw new Error(MISMATCH);
    return value;
  };
  return {
    queryParameter: 'metricResourceId' as const,
    async getDbtCatalog(
      project: string,
      release: string,
      search = '',
      page = 0,
      _size = 20,
      kind?: string,
    ) {
      const value = await api.catalog(project, release, search, kind, page + 1);
      return {
        ...value,
        page,
        size: value.size,
        resources: value.resources.map(publishedTreeResource),
      };
    },
    async getDbtResource(project: string, id: string, release: string): Promise<DbtResource> {
      const value = await resource(project, id, release);
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
      release: string,
    ): Promise<DbtResourcePresentation> {
      const value = await resource(project, id, release);
      return {
        releaseId: release,
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
    async getDbtLineage(project: string, id: string, release: string): Promise<DbtLineage> {
      const value = await detail(project, id, release, 'lineage');
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
      return { releaseId: release, resourceId: id, dependencies: value.dependencies };
    },
    async getDbtSource(project: string, id: string, release: string): Promise<DbtSourceDocument> {
      const value = await detail(project, id, release, 'source');
      if (typeof value.content !== 'string' || typeof value.path !== 'string')
        throw new Error(MISMATCH);
      return {
        releaseId: release,
        sourcePath: value.path,
        buildSourceSha: '',
        content: value.content,
      };
    },
    async previewDbtDataset(project: string, id: string, release: string, limit: number) {
      const value = await api.submit(project, {
        releaseId: release,
        idempotencyKey: crypto.randomUUID(),
        mode: PREVIEW,
        datasetResourceId: id,
        metricResourceIds: [],
        limit,
      });
      if (value.releaseId !== release) throw new Error(MISMATCH);
      return { ...publishedResult(value, PREVIEW), status: 'PENDING' as const };
    },
    async getDbtQuery(project: string, query: string) {
      const value = await api.query(project, query);
      if (value.queryId !== query) throw new Error(MISMATCH);
      return publishedResult(value, PREVIEW);
    },
  };
}
