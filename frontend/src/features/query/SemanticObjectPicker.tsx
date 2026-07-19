import { CheckOutlined, HolderOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons';
import { Button, Empty, Input, Typography } from 'antd';
import type { DragEvent } from 'react';
import type { SemanticSearchResult } from '../../api/types';
import { sortSemanticObjects, type ObjectCompatibility } from './queryCompatibility';
import './SemanticObjectPicker.css';

export type SemanticType = 'METRIC' | 'DIMENSION';
export type SemanticDragPayload = { id: string; type: SemanticType };
export const semanticDragMime = 'application/x-metric-platform-semantic';

export function SemanticObjectPicker({
  objects,
  compatibility,
  query,
  loading,
  connectivityReady,
  hasMore = false,
  onQuery,
  onAdd,
  onLoadMore,
}: {
  objects: SemanticSearchResult[];
  compatibility: Record<string, ObjectCompatibility>;
  query: string;
  loading: boolean;
  connectivityReady: boolean;
  hasMore?: boolean;
  onQuery: (value: string) => void;
  onAdd: (value: SemanticSearchResult) => void;
  onLoadMore?: () => void;
}) {
  const semanticObjects = objects.filter(
    (value): value is SemanticSearchResult & { type: SemanticType } =>
      value.type === 'METRIC' || value.type === 'DIMENSION',
  );
  const metrics = sortSemanticObjects(
    semanticObjects.filter((value) => value.type === 'METRIC'),
    compatibility,
    Boolean(query.trim()),
  );
  const dimensions = sortSemanticObjects(
    semanticObjects.filter((value) => value.type === 'DIMENSION'),
    compatibility,
    Boolean(query.trim()),
  );

  return (
    <aside className="semantic-library" aria-label="语义对象库">
      <Input.Search
        allowClear
        size="small"
        value={query}
        onChange={(event) => onQuery(event.target.value)}
        placeholder="搜索指标或维度"
        aria-label="搜索指标或维度"
      />
      <div className={loading ? 'is-loading' : undefined} aria-busy={loading}>
        <ObjectSection
          title="指标库"
          type="METRIC"
          objects={metrics}
          compatibility={compatibility}
          connectivityReady={connectivityReady}
          onAdd={onAdd}
        />
        <ObjectSection
          title="维度库"
          type="DIMENSION"
          objects={dimensions}
          compatibility={compatibility}
          connectivityReady={connectivityReady}
          onAdd={onAdd}
        />
      </div>
      {hasMore && (
        <Button
          block
          size="small"
          loading={loading}
          aria-label="加载更多语义对象"
          onClick={onLoadMore}
        >
          加载更多
        </Button>
      )}
    </aside>
  );
}

function ObjectSection({
  title,
  type,
  objects,
  compatibility,
  connectivityReady,
  onAdd,
}: {
  title: string;
  type: SemanticType;
  objects: SemanticSearchResult[];
  compatibility: Record<string, ObjectCompatibility>;
  connectivityReady: boolean;
  onAdd: (value: SemanticSearchResult) => void;
}) {
  return (
    <section className={`semantic-library-section is-${type.toLowerCase()}`} aria-label={title}>
      <header className="semantic-library-heading">
        <span>{title}</span>
        <span className="semantic-library-count">{objects.length}</span>
      </header>
      {objects.length ? (
        <div className="semantic-object-list" role="list">
          {objects.map((value) => {
            const state = compatibility[value.id] ?? {
              state: 'INCOMPATIBLE' as const,
              reason: 'RELATIONSHIP_PATH_NOT_FOUND' as const,
            };
            const addable = connectivityReady && state.state === 'ADDABLE';
            return (
              <div
                key={value.id}
                role="listitem"
                aria-disabled={!addable}
                draggable={addable}
                className={`semantic-object is-${state.state.toLowerCase()}`}
                onDragStart={(event) => addable && writeSemanticDragPayload(event, value)}
              >
                <HolderOutlined className="semantic-drag-handle" aria-hidden="true" />
                <div className="semantic-object-copy">
                  <div className="semantic-object-name" title={value.displayName}>
                    {value.displayName}
                  </div>
                  <Typography.Text
                    type="secondary"
                    className="semantic-object-detail"
                    ellipsis
                    title={secondaryText(value, state)}
                  >
                    {secondaryText(value, state)}
                  </Typography.Text>
                </div>
                <Button
                  type="text"
                  size="small"
                  disabled={!addable}
                  aria-label={`添加${value.displayName}`}
                  icon={
                    state.state === 'SELECTED' ? (
                      <CheckOutlined />
                    ) : state.state === 'ADDABLE' ? (
                      <PlusOutlined />
                    ) : (
                      <StopOutlined />
                    )
                  }
                  onClick={() => addable && onAdd(value)}
                />
              </div>
            );
          })}
        </div>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无对象" />
      )}
    </section>
  );
}

function secondaryText(value: SemanticSearchResult, state: ObjectCompatibility) {
  if (state.state !== 'INCOMPATIBLE') return value.name;
  switch (state.reason) {
    case 'SELECT_METRIC_FIRST':
      return '请先选择指标';
    case 'DIFFERENT_DATA_SOURCE':
      return '数据源不同';
    case 'AMBIGUOUS_RELATIONSHIP_PATH':
      return '存在多条关联路径';
    default:
      return '无关联路径';
  }
}

export function writeSemanticDragPayload(
  event: DragEvent,
  value: Pick<SemanticSearchResult, 'id' | 'type'>,
) {
  if (value.type !== 'METRIC' && value.type !== 'DIMENSION') return;
  event.dataTransfer.setData(semanticDragMime, JSON.stringify({ id: value.id, type: value.type }));
  event.dataTransfer.effectAllowed = 'copyMove';
}

export function readSemanticDragPayload(event: DragEvent): SemanticDragPayload | undefined {
  try {
    const value = JSON.parse(event.dataTransfer.getData(semanticDragMime)) as SemanticDragPayload;
    return value.id && (value.type === 'METRIC' || value.type === 'DIMENSION') ? value : undefined;
  } catch {
    return undefined;
  }
}
