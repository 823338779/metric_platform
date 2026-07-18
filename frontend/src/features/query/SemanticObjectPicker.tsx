import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CloseOutlined,
  HolderOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Input, Select, Spin, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useRef, useState, type DragEvent, type ReactNode } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { DimensionSelection, SemanticSearchResult, TimeGranularity } from '../../api/types';
import { placeSelection, removeSelection } from './semanticSelection';
import './SemanticObjectPicker.css';

type SemanticType = 'METRIC' | 'DIMENSION';
type DragPayload = { id: string; type: SemanticType };
type Catalog = Record<string, SemanticSearchResult>;

const dragMime = 'application/x-metric-platform-semantic';
const granularityOrder: TimeGranularity[] = ['DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR'];
const granularityLabels: Record<TimeGranularity, string> = {
  DAY: '日',
  WEEK: '周',
  MONTH: '月',
  QUARTER: '季度',
  YEAR: '年',
};

export function SemanticObjectPicker({
  metricIds,
  dimensions,
  onMetrics,
  onDimensions,
}: {
  metricIds: string[];
  dimensions: DimensionSelection[];
  onMetrics: (value: string[]) => void;
  onDimensions: (value: DimensionSelection[]) => void;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SemanticSearchResult[]>([]);
  const [catalog, setCatalog] = useState<Catalog>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [granularities, setGranularities] = useState<Record<string, TimeGranularity[]>>({});
  const requestId = useRef(0);
  const dimensionIds = useMemo(
    () => dimensions.map(({ dimensionId }) => dimensionId),
    [dimensions],
  );

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
  const reportDimensionError = (reason: unknown) => {
    setError(reason instanceof Error ? reason.message : '时间维度粒度加载失败');
  };
  const addDimension = async (id: string, index = dimensions.length) => {
    const semantic = catalog[id];
    let allowed: TimeGranularity[] = [];
    let granularity: TimeGranularity | undefined;
    if (semantic?.summary?.dimensionType === 'TIME') {
      // 搜索结果只包含摘要；仅在选中时间维度时解析完整的允许粒度。
      const resolved = await metricPlatformApi.resolveSemantic({
        objects: [{ type: 'DIMENSION', id }],
        relationshipIds: [],
      });
      const resolvedDimension = resolved.objects.find(
        (value) => value.type === 'DIMENSION' && value.id === id,
      );
      const available = new Set(resolvedDimension?.allowedGranularities ?? []);
      // 不依赖后端返回顺序，始终按日、周、月、季度、年的粒度顺序选择最小值。
      allowed = granularityOrder.filter((value) => available.has(value));
      granularity = allowed[0];
      setGranularities((current) => ({ ...current, [id]: allowed }));
    }
    const next = dimensions.filter((value) => value.dimensionId !== id);
    next.splice(Math.max(0, Math.min(index, next.length)), 0, { dimensionId: id, granularity });
    onDimensions(next);
  };
  const addDimensionSafely = (id: string, index = dimensions.length) => {
    setError('');
    void addDimension(id, index).catch(reportDimensionError);
  };
  const reorderDimensions = (ids: string[]) =>
    onDimensions(
      ids.flatMap((id) => {
        const selection = dimensions.find((value) => value.dimensionId === id);
        return selection ? [selection] : [];
      }),
    );
  const add = (value: SemanticSearchResult) => {
    if (value.type === 'METRIC') onMetrics(placeSelection(metricIds, value.id));
    if (value.type === 'DIMENSION') addDimensionSafely(value.id);
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
          onChange={reorderDimensions}
          onAdd={addDimensionSafely}
          renderExtra={(id) => {
            const semantic = catalog[id];
            if (semantic?.summary?.dimensionType !== 'TIME') return null;
            const selection = dimensions.find((value) => value.dimensionId === id);
            return (
              <Select<TimeGranularity>
                size="small"
                aria-label={`${semantic.displayName}日期粒度`}
                value={selection?.granularity}
                options={(granularities[id] ?? []).map((value) => ({
                  value,
                  label: granularityLabels[value],
                }))}
                style={{ width: 88 }}
                onChange={(granularity) =>
                  onDimensions(
                    dimensions.map((value) =>
                      value.dimensionId === id ? { ...value, granularity } : value,
                    ),
                  )
                }
              />
            );
          }}
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
  onAdd,
  renderExtra,
}: {
  type: SemanticType;
  title: string;
  description: string;
  ids: string[];
  catalog: Catalog;
  onChange: (value: string[]) => void;
  onAdd?: (id: string, index: number) => void;
  renderExtra?: (id: string) => ReactNode;
}) {
  const [dragOver, setDragOver] = useState(false);
  const place = (payload: DragPayload | undefined, index = ids.length) => {
    if (!payload) return;
    if (payload.type !== type) {
      void message.warning('指标和维度需要放入对应区域');
      return;
    }
    if (!ids.includes(payload.id) && onAdd) {
      onAdd(payload.id, index);
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
                  {renderExtra?.(id)}
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
