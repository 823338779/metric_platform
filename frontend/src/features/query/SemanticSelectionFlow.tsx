import { CloseOutlined, HolderOutlined } from '@ant-design/icons';
import { Button, Select, Typography } from 'antd';
import type { DragEvent, ReactNode } from 'react';
import type { DimensionSelection, SemanticSearchResult, TimeGranularity } from '../../api/types';
import { removeSelection } from './semanticSelection';
import {
  readSemanticDragPayload,
  writeSemanticDragPayload,
  type SemanticType,
} from './SemanticObjectPicker';

const granularityLabels: Record<TimeGranularity, string> = {
  DAY: '日',
  WEEK: '周',
  MONTH: '月',
  QUARTER: '季度',
  YEAR: '年',
};

export function SemanticSelectionFlow({
  metricIds,
  dimensions,
  catalog,
  granularities,
  onMetrics,
  onDimensions,
  onPlaceMetric,
  onPlaceDimension,
}: {
  metricIds: string[];
  dimensions: DimensionSelection[];
  catalog: Record<string, SemanticSearchResult>;
  granularities: Record<string, TimeGranularity[]>;
  onMetrics: (ids: string[]) => void;
  onDimensions: (value: DimensionSelection[]) => void;
  onPlaceMetric: (id: string, index?: number) => void;
  onPlaceDimension: (id: string, index?: number) => void;
}) {
  const dimensionIds = dimensions.map((value) => value.dimensionId);
  return (
    <div className="semantic-selection-flow" aria-label="已选查询对象">
      <SelectionGroup
        label="指标"
        type="METRIC"
        ids={metricIds}
        catalog={catalog}
        onChange={onMetrics}
        onPlace={onPlaceMetric}
      />
      <span className="semantic-selection-divider" aria-hidden="true" />
      <SelectionGroup
        label="维度"
        type="DIMENSION"
        ids={dimensionIds}
        catalog={catalog}
        onChange={(ids) =>
          onDimensions(
            ids.flatMap((id) => {
              const selection = dimensions.find((value) => value.dimensionId === id);
              return selection ? [selection] : [];
            }),
          )
        }
        onPlace={onPlaceDimension}
        renderExtra={(id) => {
          const options = granularities[id];
          if (!options?.length) return null;
          const selection = dimensions.find((value) => value.dimensionId === id);
          return (
            <Select<TimeGranularity>
              size="small"
              className="semantic-granularity-select"
              popupClassName="semantic-granularity-popup"
              aria-label={`${catalog[id]?.displayName ?? id}日期粒度`}
              value={selection?.granularity}
              options={options.map((value) => ({ value, label: granularityLabels[value] }))}
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
  );
}

function SelectionGroup({
  label,
  type,
  ids,
  catalog,
  onChange,
  onPlace,
  renderExtra,
}: {
  label: string;
  type: SemanticType;
  ids: string[];
  catalog: Record<string, SemanticSearchResult>;
  onChange: (ids: string[]) => void;
  onPlace: (id: string, index?: number) => void;
  renderExtra?: (id: string) => ReactNode;
}) {
  const drop = (event: DragEvent, index = ids.length) => {
    event.preventDefault();
    const payload = readSemanticDragPayload(event);
    if (payload?.type === type) onPlace(payload.id, index);
  };
  return (
    <div
      className={`semantic-selection-group is-${type.toLowerCase()}`}
      onDragOver={(event) => event.preventDefault()}
      onDrop={(event) => drop(event)}
    >
      <Typography.Text type="secondary" className="semantic-selection-label">
        {label}
      </Typography.Text>
      {!ids.length && <span className="semantic-selection-empty">未选择</span>}
      {ids.map((id, index) => {
        const value = catalog[id];
        const displayName = value?.displayName ?? id;
        return (
          <div
            key={id}
            className={`semantic-selection is-${type.toLowerCase()}`}
            draggable
            onDragStart={(event) => writeSemanticDragPayload(event, { id, type })}
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => drop(event, index)}
          >
            <HolderOutlined aria-hidden="true" />
            <span className="semantic-selection-name" title={displayName}>
              {displayName}
            </span>
            {renderExtra?.(id)}
            <Button
              type="text"
              danger
              size="small"
              icon={<CloseOutlined />}
              aria-label={`移除${displayName}`}
              onClick={() => onChange(removeSelection(ids, id))}
            />
          </div>
        );
      })}
    </div>
  );
}
