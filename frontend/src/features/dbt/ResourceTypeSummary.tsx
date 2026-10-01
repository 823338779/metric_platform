import { useEffect, useState } from 'react';
import { Tag } from 'antd';
import type { DbtLineage, DbtResourcePresentation } from '../../api/types';
import {
  resolveResourceDataType,
  type DataTypeApi,
  type ResourceDataType as DataType,
} from './resourceDataType';

const COPY = {
  title: '数据类型',
  loading: '读取中…',
  failure: '读取失败',
  failureHint: '暂时无法读取类型元数据，请重新选择资源后重试。',
};

export function ResourceTypeSummary({
  presentation,
  lineage,
  projectId,
  api,
  onResolve,
}: {
  presentation: DbtResourcePresentation;
  lineage?: DbtLineage;
  projectId: string;
  api: DataTypeApi;
  onResolve?: (value: DataType) => void;
}) {
  const [value, setValue] = useState<DataType>();
  useEffect(() => {
    let active = true;
    setValue(undefined);
    void resolveResourceDataType(presentation, lineage, projectId, api)
      .then((result) => {
        if (active) {
          setValue(result);
          onResolve?.(result);
        }
      })
      .catch(() => {
        if (active) setValue({ label: COPY.failure, description: COPY.failureHint });
      });
    return () => {
      active = false;
    };
  }, [presentation, lineage, projectId, api, onResolve]);
  return (
    <section className="resource-data-type" aria-label={COPY.title}>
      <span className="resource-data-type-label">{COPY.title}</span>
      <div aria-live="polite">
        <Tag color="geekblue">{value?.label ?? COPY.loading}</Tag>
        {value && <p>{value.description}</p>}
      </div>
    </section>
  );
}
