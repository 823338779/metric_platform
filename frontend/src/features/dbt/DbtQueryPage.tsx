import { Alert, Button, Card, Input, InputNumber, Space, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtQueryStatus } from '../../api/types';

type QueryApi = Pick<MetricPlatformApi, 'submitDbtQuery' | 'explainDbtQuery' | 'getDbtQuery'>;

/** 查询提交后按 queryId 轮询，前后始终使用 URL 中固定的 releaseId。 */
export function DbtQueryPage({ api = metricPlatformApi }: { api?: QueryApi }) {
  const { projectId = '' } = useParams();
  const [params] = useSearchParams();
  const releaseId = params.get('releaseId') ?? '';
  const [metrics, setMetrics] = useState(params.get('metric') ?? '');
  const [groupBy, setGroupBy] = useState('');
  const [limit, setLimit] = useState(100);
  const [queryId, setQueryId] = useState('');
  const [result, setResult] = useState<DbtQueryStatus>();
  const [error, setError] = useState('');
  useEffect(() => {
    if (!queryId) return;
    let cancelled = false;
    const poll = () => {
      void api
        .getDbtQuery(projectId, queryId)
        .then((value) => {
          if (!cancelled) setResult(value);
        })
        .catch((cause: unknown) => {
          if (!cancelled)
            setError(
              cause instanceof ApiProblem && cause.problem.status === 410
                ? '该发布版本已清理'
                : '查询状态读取失败',
            );
        });
    };
    poll();
    const timer = window.setInterval(() => {
      if (
        result?.status !== 'COMPLETED' &&
        result?.status !== 'FAILED' &&
        result?.status !== 'CANCELLED'
      )
        poll();
    }, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [api, projectId, queryId, result?.status]);
  const submit = async (explain: boolean) => {
    if (!releaseId) {
      setError('缺少发布版本 releaseId');
      return;
    }
    setError('');
    setResult(undefined);
    const body = {
      releaseId,
      metrics: metrics
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean),
      groupBy: groupBy
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean),
      limit,
    };
    try {
      const submission = explain
        ? await api.explainDbtQuery(projectId, body)
        : await api.submitDbtQuery(projectId, body);
      setQueryId(submission.queryId);
    } catch (cause) {
      setError(
        cause instanceof ApiProblem && cause.problem.status === 410
          ? '该发布版本已清理'
          : '查询提交失败',
      );
    }
  };
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {error && <Alert type="error" title={error} />}
      <Card
        title="dbt 固定版本查询"
        extra={<Typography.Text>releaseId：{releaseId || '未选择'}</Typography.Text>}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input
            aria-label="指标"
            placeholder="指标名称，多个用逗号分隔"
            value={metrics}
            onChange={(event) => setMetrics(event.target.value)}
          />
          <Input
            aria-label="维度"
            placeholder="维度名称，多个用逗号分隔"
            value={groupBy}
            onChange={(event) => setGroupBy(event.target.value)}
          />
          <InputNumber
            aria-label="行数"
            min={1}
            max={10000}
            value={limit}
            onChange={(value) => setLimit(value ?? 100)}
          />
          <Space>
            <Button type="primary" onClick={() => void submit(false)}>
              运行查询
            </Button>
            <Button onClick={() => void submit(true)}>解释查询</Button>
          </Space>
        </Space>
      </Card>
      {queryId && (
        <Card title="查询结果" extra={<Typography.Text>queryId：{queryId}</Typography.Text>}>
          <Typography.Text>状态：{result?.status ?? 'PENDING'}</Typography.Text>
          {result?.errorCode && <Alert type="error" title={result.errorCode} />}
          {result?.columns && result.rows && (
            <Table
              pagination={false}
              columns={result.columns.map((column, index) => ({
                title: `${column.name} (${column.type})`,
                dataIndex: index.toString(),
              }))}
              dataSource={result.rows.map((row, rowIndex) => ({
                key: rowIndex,
                ...Object.fromEntries(
                  row.map((cell, index) => [index.toString(), String(cell ?? '')]),
                ),
              }))}
            />
          )}
        </Card>
      )}
    </Space>
  );
}
