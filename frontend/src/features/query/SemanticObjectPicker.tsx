import { Select, Space, Tag } from 'antd';
import { useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { SemanticSearchResult } from '../../api/types';
export function SemanticObjectPicker({
  metricIds,
  dimensionIds,
  onMetrics,
  onDimensions,
}: {
  metricIds: string[];
  dimensionIds: string[];
  onMetrics: (v: string[]) => void;
  onDimensions: (v: string[]) => void;
}) {
  const [options, setOptions] = useState<SemanticSearchResult[]>([]);
  const search = (q: string) => void metricPlatformApi.searchSemantic(q).then(setOptions);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Select
        mode="multiple"
        showSearch
        filterOption={false}
        onSearch={search}
        value={metricIds}
        onChange={onMetrics}
        placeholder="搜索并选择多个指标"
        options={options
          .filter((v) => v.type === 'METRIC')
          .map((v) => ({
            value: v.id,
            label: (
              <span>
                <Tag color="purple">指标</Tag>
                {v.displayName}
              </span>
            ),
          }))}
      />
      <Select
        mode="multiple"
        showSearch
        filterOption={false}
        onSearch={search}
        value={dimensionIds}
        onChange={onDimensions}
        placeholder="搜索并选择维度"
        options={options
          .filter((v) => v.type === 'DIMENSION')
          .map((v) => ({
            value: v.id,
            label: (
              <span>
                <Tag color="blue">维度</Tag>
                {v.displayName}
              </span>
            ),
          }))}
      />
    </Space>
  );
}
