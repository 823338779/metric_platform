import { createBuildCatalogApi } from './buildCatalogApi';
import type { Fetcher } from './httpClient';
import { publishedResourceAdapter } from '../features/dbt/publishedResourceAdapter';

/** 现有目录组件的显示适配器，唯一 HTTP 实现位于 buildCatalogApi。 */
export function createMetricPlatformApi(fetcher?: Fetcher) {
  return publishedResourceAdapter(createBuildCatalogApi(fetcher));
}
export const metricPlatformApi = createMetricPlatformApi();
export type MetricPlatformApi = ReturnType<typeof createMetricPlatformApi>;
