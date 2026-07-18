import { Alert, Button, Card, Space, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { QueryRequest, QueryResponse, ValidationResponse } from '../../api/types';
import { PlanExplanation } from './PlanExplanation';
import { QueryResultTable } from './QueryResultTable';
import { SemanticObjectPicker } from './SemanticObjectPicker';
export function QueryWorkbenchPage() {
  const [metricIds, setMetrics] = useState<string[]>([]);
  const [dimensionIds, setDimensions] = useState<string[]>([]);
  const [plan, setPlan] = useState<ValidationResponse>();
  const [result, setResult] = useState<QueryResponse>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const request = useMemo<QueryRequest>(
    () => ({
      metricIds,
      dimensions: dimensionIds.map((dimensionId) => ({ dimensionId })),
      relationshipIds: [],
    }),
    [metricIds, dimensionIds],
  );
  useEffect(() => {
    setPlan(undefined);
    setResult(undefined);
    if (!metricIds.length) return;
    const timer = setTimeout(
      () =>
        void metricPlatformApi
          .validateQuery(request)
          .then(setPlan)
          .catch((e) => setError(e instanceof Error ? e.message : '验证失败')),
      300,
    );
    return () => clearTimeout(timer);
  }, [request]);
  const execute = async () => {
    setLoading(true);
    setError('');
    try {
      const validated = await metricPlatformApi.validateQuery(request);
      setPlan(validated);
      setResult(await metricPlatformApi.executeQuery(request));
    } catch (e) {
      setError(e instanceof Error ? e.message : '查询失败');
    } finally {
      setLoading(false);
    }
  };
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="查询工作台">
        <Typography.Paragraph type="secondary">
          跨数据集选择指标和维度，平台会自动沿已校验关系规划 LEFT
          JOIN，并在各事实数据集预聚合后合并结果。
        </Typography.Paragraph>
        <SemanticObjectPicker
          metricIds={metricIds}
          dimensionIds={dimensionIds}
          onMetrics={setMetrics}
          onDimensions={setDimensions}
        />
        <Button
          type="primary"
          disabled={!metricIds.length || !plan?.valid}
          loading={loading}
          onClick={() => void execute()}
          style={{ marginTop: 16 }}
        >
          执行查询
        </Button>
      </Card>
      {error && <Alert type="error" showIcon message="查询无法完成" description={error} />}{' '}
      {plan && <PlanExplanation plan={plan} />}
      <Card title="结果">
        <QueryResultTable result={result} loading={loading} />
      </Card>
    </Space>
  );
}
