import type {
  BuildCatalogApi,
  NativeCatalog,
  NativeNode,
  PublishedResource,
  PublishedResult,
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
const COMPLETED = 'COMPLETED';

export function publishedResult(
  result: PublishedResult,
  mode = result.mode ?? QUERY,
): DbtQueryStatus {
  return {
    buildId: result.buildId,
    targetCommitSha: result.commitSha ?? '',
    mode,
    status: COMPLETED,
    columns: result.columns ?? null,
    rows: result.rows ?? null,
    errorCode: null,
    errorMessage: null,
    truncated: result.resultTruncated ?? null,
    durationMs: null,
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

const METRIC = 'metric';
const MODEL = 'model';
const SOURCE = 'source';
const SEED = 'seed';
const SNAPSHOT = 'snapshot';
const NATIVE_SEMANTIC_MODEL = 'semantic_model';
const VIEW = 'view';
const NATIVE_DEPENDENCY = 'native';
const FQN_SELECTOR = 'fqn:';
const SOURCE_SELECTOR = 'source:';
const DOT = '.';
const STRING = 'string';

/** selector 使用 dbt FQN，unique_id 的 model 前缀不是模型的 FQN。 */
function nativeSelector(node: NativeNode): string {
  if (node.resource_type === SOURCE) {
    const identity = [node.package_name, node.source_name, node.name];
    if (identity.some((part) => typeof part !== STRING || !part))
      throw new Error('数据源缺少完整的原生名称');
    return SOURCE_SELECTOR + identity.join(DOT);
  }
  const fqn =
    Array.isArray(node.fqn) && node.fqn.every((part) => typeof part === STRING)
      ? node.fqn.join(DOT)
      : node.name;
  if (!fqn) throw new Error('资源缺少可选择的原生名称');
  return FQN_SELECTOR + fqn;
}

/** dbt unique_id 是唯一资源身份；维度留在 semantic_model 内，不伪造服务资源。 */
export function nativeResource(id: string, node: NativeNode): PublishedResource {
  const kind: PublishedResource['kind'] =
    node.resource_type === METRIC
      ? 'METRIC'
      : node.resource_type === NATIVE_SEMANTIC_MODEL
        ? SEMANTIC_MODEL
        : node.config?.materialized === VIEW
          ? 'VIEW'
          : 'TABLE';
  return {
    resourceId: id,
    nativeId: id,
    kind,
    name: node.name ?? id,
    displayName: node.label ?? node.name ?? id,
    description: node.description ?? null,
    sourcePath: node.original_file_path ?? null,
    capabilities:
      node.resource_type === METRIC ? [QUERY] : node.resource_type === MODEL ? [PREVIEW] : [],
    attributes: node,
    nativeDetails: node,
  };
}
export function catalogResources(catalog: NativeCatalog): PublishedResource[] {
  return catalog
    .filter((node) =>
      [METRIC, MODEL, SOURCE, SEED, SNAPSHOT, NATIVE_SEMANTIC_MODEL].includes(
        node.resource_type ?? '',
      ),
    )
    .map((node) => nativeResource(node.unique_id, node));
}

/** 原生协议在展示边界转换；缓存固定构建的不可变资源列表 和资源，避免重复拉取。 */
export function publishedResourceAdapter(api: BuildCatalogApi) {
  const catalogs = new Map<string, Promise<NativeCatalog>>();
  const resources = new Map<string, ReturnType<BuildCatalogApi['resource']>>();
  const resource = async (_project: string, id: string, buildId: string) => {
    const key = JSON.stringify([buildId, id]);
    let pending = resources.get(key);
    if (!pending) {
      pending = api.resource(buildId, id);
      resources.set(key, pending);
      pending.catch(() => resources.delete(key));
    }
    const value = await pending;
    if (value.buildId !== buildId || value.resourceId !== id) throw new Error(MISMATCH);
    return value;
  };
  return {
    queryParameter: 'metricResourceId' as const,
    async getDbtCatalog(
      _project: string,
      buildId: string,
      search = '',
      page = 0,
      size = 20,
      kind?: string,
    ) {
      let pending = catalogs.get(buildId);
      if (!pending) {
        pending = api.catalog(buildId);
        catalogs.set(buildId, pending);
        pending.catch(() => catalogs.delete(buildId));
      }
      const all = catalogResources(await pending).filter(
        (item) =>
          (!kind || item.kind === kind) &&
          (!search ||
            `${item.name} ${item.displayName} ${item.description ?? ''}`
              .toLowerCase()
              .includes(search.toLowerCase())),
      );
      return {
        buildId,
        page,
        size,
        total: all.length,
        resources: all.slice(page * size, (page + 1) * size).map(publishedTreeResource),
      };
    },
    async getDbtResource(project: string, id: string, buildId: string): Promise<DbtResource> {
      const value = await resource(project, id, buildId);
      return publishedTreeResource(nativeResource(id, value.resource));
    },
    async getDbtPresentation(
      project: string,
      id: string,
      buildId: string,
    ): Promise<DbtResourcePresentation> {
      const value = nativeResource(id, (await resource(project, id, buildId)).resource);
      return {
        buildId,
        resourceId: id,
        kind: value.kind,
        title: value.name,
        queryable: value.capabilities.includes(QUERY) || value.capabilities.includes(PREVIEW),
        nativeDetails: value.nativeDetails,
        sections: [{ title: RESOURCE_LABELS[value.kind] ?? value.kind, fields: value.attributes }],
      };
    },
    async getDbtLineage(_project: string, id: string, buildId: string): Promise<DbtLineage> {
      const value = await resource(_project, id, buildId);
      const nodes = await api.lineage(buildId, `+${nativeSelector(value.resource)}+`);
      if (!Array.isArray(nodes)) throw new Error(MISMATCH);
      return {
        buildId,
        resourceId: id,
        dependencies: nodes.flatMap((node) =>
          (node.depends_on?.nodes ?? []).map((upstreamResourceId) => ({
            upstreamResourceId,
            downstreamResourceId: node.unique_id ?? '',
            kind: NATIVE_DEPENDENCY,
          })),
        ),
      };
    },
    async getDbtSource(project: string, id: string, buildId: string): Promise<DbtSourceDocument> {
      const value = await resource(project, id, buildId);
      return {
        buildId,
        sourcePath: value.source?.path ?? '',
        buildSourceSha: '',
        content: value.source?.content ?? '',
      };
    },
    async previewDbtDataset(_project: string, id: string, buildId: string, limit: number) {
      const node = await resource(_project, id, buildId);
      const value = await api.submit(buildId, {
        mode: PREVIEW,
        selector: nativeSelector(node.resource),
        limit,
      });
      if (value.buildId !== buildId) throw new Error(MISMATCH);
      return publishedResult(value, PREVIEW);
    },
  };
}
