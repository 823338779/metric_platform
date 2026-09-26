import { Alert, Button, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtQueryFilter } from '../../api/types';

type ValuesApi = Pick<MetricPlatformApi, 'submitDbtDimensionValues' | 'getDbtQuery'>;

/** 维度值查询也是固定 release 的异步任务，截断结果需显式提示。 */
export function DbtDimensionValues({
  projectId,
  releaseId,
  metrics,
  dimension,
  filters = [],
  onSelect,
  api = metricPlatformApi,
}: {
  projectId: string;
  releaseId: string;
  metrics: string[];
  dimension: string;
  filters?: DbtQueryFilter[];
  onSelect: (value: unknown) => void;
  api?: ValuesApi;
}) {
  const [values, setValues] = useState<unknown[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setValues([]);
    setTruncated(false);
    setError('');
    return () => {
      generation.current++;
    };
  }, [releaseId, dimension, metrics.join(','), JSON.stringify(filters)]);
  const load = async () => {
    const request = ++generation.current;
    setLoading(true);
    setError('');
    setValues([]);
    try {
      const job = await api.submitDbtDimensionValues(projectId, {
        releaseId,
        metrics,
        dimension,
        filters,
        limit: 100,
      });
      if (job.releaseId !== releaseId || request !== generation.current) return;
      for (let attempt = 0; attempt < 30; attempt++) {
        const status = await api.getDbtQuery(projectId, job.queryId);
        if (status.releaseId !== releaseId || request !== generation.current) return;
        if (status.status === 'COMPLETED') {
          setValues((status.rows ?? []).map((row) => row[0]));
          setTruncated(Boolean(status.truncated));
          return;
        }
        if (status.status === 'FAILED' || status.status === 'CANCELLED') {
          setError(status.errorCode ?? '维度值查询失败');
          return;
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
      }
      setError('维度值查询超时');
    } catch {
      if (request === generation.current) setError('维度值查询失败');
    } finally {
      if (request === generation.current) setLoading(false);
    }
  };
  return (
    <Space direction="vertical">
      <Button
        disabled={!dimension || !metrics.length}
        loading={loading}
        onClick={() => void load()}
      >
        查询维度值
      </Button>
      {error && <Alert type="error" title={error} />}
      {truncated && <Alert type="warning" title="维度值已截断，请增加筛选条件" />}
      {values.length > 0 && (
        <>
          <Typography.Text>选择筛选值</Typography.Text>
          <Select
            aria-label="维度值"
            style={{ minWidth: 180 }}
            showSearch
            options={values.map((value) => ({
              value: String(value ?? ''),
              label: String(value ?? ''),
            }))}
            onChange={(value) => onSelect(value)}
          />
        </>
      )}
    </Space>
  );
}
