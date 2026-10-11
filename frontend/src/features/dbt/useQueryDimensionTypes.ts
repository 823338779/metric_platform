import { useEffect, useState } from 'react';
import type { BuildCatalogApi, PublishedOption } from '../../api/buildCatalogApi';
import { publishedResourceAdapter } from './publishedResourceAdapter';
import { resolveResourceDataType } from './resourceDataType';

const UNKNOWN = '待确认';

/** 类型只来自查询所固定版本的资源，元数据失败不影响已有查询能力。 */
export function useQueryDimensionTypes(
  api: BuildCatalogApi,
  projectId: string,
  buildId: string,
  availableDimensions: PublishedOption[],
) {
  const [types, setTypes] = useState<Record<string, string>>({});
  useEffect(() => {
    let active = true;
    setTypes({});
    const adapter = publishedResourceAdapter(api);
    const ids = [
      ...new Set(
        availableDimensions.flatMap((option) => (option.resourceId ? [option.resourceId] : [])),
      ),
    ];
    void Promise.all(
      ids.map(async (id) => {
        try {
          const presentation = await adapter.getDbtPresentation(projectId, id, buildId);
          let type = await resolveResourceDataType(presentation, undefined, projectId, adapter);
          if (type.label === UNKNOWN) {
            const lineage = await adapter.getDbtLineage(projectId, id, buildId);
            type = await resolveResourceDataType(presentation, lineage, projectId, adapter);
          }
          return type.label === UNKNOWN ? null : ([id, type.label] as const);
        } catch {
          return null;
        }
      }),
    ).then((entries) => {
      if (active) setTypes(Object.fromEntries(entries.filter((entry) => entry !== null)));
    });
    return () => {
      active = false;
    };
  }, [api, projectId, buildId, availableDimensions]);
  return types;
}
