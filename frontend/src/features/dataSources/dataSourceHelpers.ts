import type { DataSourceStatus, HealthStatus, SourceLocation } from '../../api/types';

export type CatalogNodeType = 'source' | 'catalog' | 'schema' | 'object' | 'field';
export function treeKey(
  type: CatalogNodeType,
  sourceId: string,
  location: Partial<SourceLocation> & { fieldName?: string } = {},
) {
  return JSON.stringify([
    type,
    sourceId,
    location.catalogName ?? null,
    location.schemaName ?? null,
    location.objectName ?? null,
    location.fieldName ?? null,
  ]);
}
export const sourceStatusLabel: Record<DataSourceStatus, string> = {
  ACTIVE: '已启用',
  INACTIVE: '已停用',
  DELETED: '已删除',
};
export const healthLabel: Record<HealthStatus, string> = {
  UNKNOWN: '未检测',
  HEALTHY: '连接正常',
  UNHEALTHY: '连接异常',
};
export const healthColor: Record<HealthStatus, string> = {
  UNKNOWN: 'default',
  HEALTHY: 'success',
  UNHEALTHY: 'error',
};
export function safeDataSourceError(error: unknown) {
  if (error instanceof DOMException && error.name === 'AbortError') return '';
  const title =
    typeof error === 'object' && error && 'problem' in error
      ? String((error as { problem?: { title?: string } }).problem?.title ?? '')
      : '';
  if (title.includes('timed out')) return '源库响应超时，请稍后重试或减少预览行数。';
  if (title.includes('unavailable')) return '数据源已停用或当前不可用。';
  if (title.includes('Resource not found')) return '对象已不存在，请刷新上级目录。';
  return '请求失败，请检查连接状态后重试。';
}
