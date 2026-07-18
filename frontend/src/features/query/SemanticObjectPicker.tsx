import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CloseOutlined,
  HolderOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Input, Spin, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { SemanticSearchResult } from '../../api/types';
import { placeSelection, removeSelection } from './semanticSelection';
import './SemanticObjectPicker.css';

type SemanticType = 'METRIC' | 'DIMENSION';
type DragPayload = { id: string; type: SemanticType };
type Catalog = Record<string, SemanticSearchResult>;

const dragMime = 'application/x-metric-platform-semantic';

export function SemanticObjectPicker({
  metricIds,
  dimensionIds,
  onMetrics,
  onDimensions,
}: {
  metricIds: string[];
  dimensionIds: string[];
  onMetrics: (value: string[]) => void;
  onDimensions: (value: string[]) => void;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SemanticSearchResult[]>([]);
  const [catalog, setCatalog] = useState<Catalog>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const requestId = useRef(0);

  useEffect(() => {
    const current = ++requestId.current;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError('');
      void metricPlatformApi
        .searchSemantic(query)
        .then((values) => {
          if (current !== requestId.current) return;
          const semanticValues = values.filter(
            (value): value is SemanticSearchResult & { type: SemanticType } =>
              value.type === 'METRIC' || value.type === 'DIMENSION',
          );
          setResults(semanticValues);
          setCatalog((previous) => ({
            ...previous,
            ...Object.fromEntries(semanticValues.map((value) => [value.id, value])),
          }));
        })
        .catch((reason) => {
          if (current === requestId.current)
            setError(reason instanceof Error ? reason.message : '语义对象加载失败');
        })
        .finally(() => {
          if (current === requestId.current) setLoading(false);
        });
    }, 200);
    return () => window.clearTimeout(timer);
  }, [query]);

  const selected = useMemo(
    () => new Set([...metricIds, ...dimensionIds]),
    [metricIds, dimensionIds],
  );
  const add = (value: SemanticSearchResult) => {
    if (value.type === 'METRIC') onMetrics(placeSelection(metricIds, value.id));
    if (value.type === 'DIMENSION') onDimensions(placeSelection(dimensionIds, value.id));
  };

  return (
    <div className="semantic-builder">
      <section className="semantic-library" aria-label="可用语义对象">
        <div className="semantic-panel-heading">
          <div>
            <Typography.Text strong>可用对象</Typography.Text>
            <Typography.Text type="secondary" className="semantic-heading-help">
              拖到右侧或点击添加
            </Typography.Text>
          </div>
          <Tag>{results.length}</Tag>
        </div>
        <Input.Search
          allowClear
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索指标或维度"
          aria-label="搜索指标或维度"
        />
        {error && (
          <Alert type="error" showIcon message={error} className="semantic-library-error" />
        )}
        <Spin spinning={loading}>
          <div className="semantic-object-list" role={results.length ? 'list' : undefined}>
            {!loading && !results.length ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的语义对象" />
            ) : (
              results.map((value) => (
                <div
                  key={value.id}
                  className={`semantic-object ${selected.has(value.id) ? 'is-selected' : ''}`}
                  draggable
                  role="listitem"
                  onDragStart={(event) => writeDragPayload(event, value)}
                >
                  <HolderOutlined className="semantic-drag-handle" aria-hidden="true" />
                  <div className="semantic-object-copy">
                    <div className="semantic-object-name" title={value.displayName}>
                      {value.displayName}
                    </div>
                    <div className="semantic-object-meta">
                      <Tag color={value.type === 'METRIC' ? 'purple' : 'blue'}>
                        {value.type === 'METRIC' ? '指标' : '维度'}
                      </Tag>
                      <Typography.Text type="secondary" ellipsis title={value.name}>
                        {value.name}
                      </Typography.Text>
                    </div>
                  </div>
                  <Button
                    type="text"
                    size="small"
                    icon={<PlusOutlined />}
                    disabled={selected.has(value.id)}
                    aria-label={`添加${value.displayName}`}
                    onClick={() => add(value)}
                  >
                    {selected.has(value.id) ? '已添加' : '添加'}
                  </Button>
                </div>
              ))
            )}
          </div>
        </Spin>
      </section>

      <div className="semantic-query-canvas">
        <SemanticDropZone
          type="METRIC"
          title="指标"
          description="至少选择一个，可拖动调整顺序"
          ids={metricIds}
          catalog={catalog}
          onChange={onMetrics}
        />
        <SemanticDropZone
          type="DIMENSION"
          title="维度"
          description="可选，用于分组查询结果"
          ids={dimensionIds}
          catalog={catalog}
          onChange={onDimensions}
        />
      </div>
    </div>
  );
}

function SemanticDropZone({
  type,
  title,
  description,
  ids,
  catalog,
  onChange,
}: {
  type: SemanticType;
  title: string;
  description: string;
  ids: string[];
  catalog: Catalog;
  onChange: (value: string[]) => void;
}) {
  const [dragOver, setDragOver] = useState(false);
  const place = (payload: DragPayload | undefined, index = ids.length) => {
    if (!payload) return;
    if (payload.type !== type) {
      void message.warning('指标和维度需要放入对应区域');
      return;
    }
    onChange(placeSelection(ids, payload.id, index));
  };
  const dropAt = (event: DragEvent, index: number) => {
    event.preventDefault();
    event.stopPropagation();
    setDragOver(false);
    place(readDragPayload(event), index);
  };

  return (
    <section
      className={`semantic-drop-zone ${dragOver ? 'is-drag-over' : ''}`}
      aria-label={`${title}投放区`}
      onDragOver={(event) => {
        event.preventDefault();
        setDragOver(event.dataTransfer.types.includes(dragTypeMarker(type)));
      }}
      onDragLeave={(event) => {
        const related = event.relatedTarget;
        if (!(related instanceof Node) || !event.currentTarget.contains(related))
          setDragOver(false);
      }}
      onDrop={(event) => dropAt(event, ids.length)}
    >
      <div className="semantic-zone-heading">
        <div>
          <Typography.Text strong>
            {title} {type === 'METRIC' && <span className="semantic-required">*</span>}
          </Typography.Text>
          <Typography.Text type="secondary" className="semantic-heading-help">
            {description}
          </Typography.Text>
        </div>
        <Tag color={type === 'METRIC' ? 'purple' : 'blue'}>{ids.length}</Tag>
      </div>
      <div className="semantic-selected-list" role={ids.length ? 'list' : undefined}>
        {!ids.length ? (
          <div className="semantic-zone-empty">将{title}拖到这里</div>
        ) : (
          ids.map((id, index) => {
            const value = catalog[id];
            const displayName = value?.displayName ?? id;
            return (
              <div
                key={id}
                className="semantic-selected-object"
                draggable
                role="listitem"
                onDragStart={(event) => writeDragPayload(event, { id, type })}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => dropAt(event, index)}
              >
                <HolderOutlined className="semantic-drag-handle" aria-hidden="true" />
                <div className="semantic-object-copy">
                  <div className="semantic-object-name" title={displayName}>
                    {displayName}
                  </div>
                  <Typography.Text type="secondary" ellipsis title={value?.name ?? id}>
                    {value?.name ?? id}
                  </Typography.Text>
                </div>
                <div className="semantic-item-actions">
                  <Button
                    type="text"
                    size="small"
                    icon={<ArrowUpOutlined />}
                    disabled={index === 0}
                    aria-label={`上移${displayName}`}
                    onClick={() => onChange(placeSelection(ids, id, index - 1))}
                  />
                  <Button
                    type="text"
                    size="small"
                    icon={<ArrowDownOutlined />}
                    disabled={index === ids.length - 1}
                    aria-label={`下移${displayName}`}
                    onClick={() => onChange(placeSelection(ids, id, index + 1))}
                  />
                  <Button
                    type="text"
                    danger
                    size="small"
                    icon={<CloseOutlined />}
                    aria-label={`移除${displayName}`}
                    onClick={() => onChange(removeSelection(ids, id))}
                  />
                </div>
              </div>
            );
          })
        )}
      </div>
    </section>
  );
}

function writeDragPayload(event: DragEvent, value: Pick<SemanticSearchResult, 'id' | 'type'>) {
  if (value.type !== 'METRIC' && value.type !== 'DIMENSION') return;
  event.dataTransfer.setData(dragMime, JSON.stringify({ id: value.id, type: value.type }));
  event.dataTransfer.setData(dragTypeMarker(value.type), '1');
  event.dataTransfer.effectAllowed = 'copyMove';
}

function dragTypeMarker(type: SemanticType) {
  return `${dragMime}-${type.toLowerCase()}`;
}

function readDragPayload(event: DragEvent): DragPayload | undefined {
  try {
    const value = JSON.parse(event.dataTransfer.getData(dragMime)) as DragPayload;
    return value.id && (value.type === 'METRIC' || value.type === 'DIMENSION') ? value : undefined;
  } catch {
    return undefined;
  }
}
