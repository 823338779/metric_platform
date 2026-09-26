import { Alert, Button, Card, Descriptions, Space, Tabs, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type {
  DbtLineage,
  DbtQueryStatus,
  DbtResourcePresentation,
  DbtSourceDocument,
} from '../../api/types';
import { DbtLineageGraph } from './DbtLineageGraph';
import { DbtQueryResults } from './DbtQueryResults';

type DetailApi = Pick<
  MetricPlatformApi,
  'getDbtPresentation' | 'getDbtSource' | 'getDbtLineage' | 'previewDbtDataset' | 'getDbtQuery'
>;

const physicalKinds = new Set(['TABLE', 'VIEW', 'MODEL']);
const text = (value: unknown) =>
  typeof value === 'string' ? value : JSON.stringify(value, null, 2);

/** 同一详情内的产物、源码和血缘均固定在目录 releaseId。 */
export function DbtResourceDetail({
  projectId,
  releaseId,
  resourceId,
  onSelect,
  api = metricPlatformApi,
}: {
  projectId: string;
  releaseId: string;
  resourceId: string;
  onSelect: (resourceId: string) => void;
  api?: DetailApi;
}) {
  const [presentation, setPresentation] = useState<DbtResourcePresentation>();
  const [source, setSource] = useState<DbtSourceDocument>();
  const [lineage, setLineage] = useState<DbtLineage>();
  const [preview, setPreview] = useState<DbtQueryStatus>();
  const [previewQueryId, setPreviewQueryId] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setPresentation(undefined);
    setSource(undefined);
    setLineage(undefined);
    setPreview(undefined);
    setPreviewQueryId('');
    setError('');
    void api
      .getDbtPresentation(projectId, resourceId, releaseId)
      .then((value) => {
        if (active && value.releaseId === releaseId) setPresentation(value);
        else if (active) setError('资源详情版本与请求不一致');
      })
      .catch((cause: unknown) => {
        if (active)
          setError(
            cause instanceof ApiProblem && cause.problem.status === 410
              ? '该发布版本已清理'
              : '资源详情读取失败',
          );
      });
    void api
      .getDbtLineage(projectId, resourceId, releaseId)
      .then((value) => {
        if (active && value.releaseId === releaseId) setLineage(value);
      })
      .catch(() => {
        if (active) setLineage({ releaseId, resourceId, dependencies: [] });
      });
    return () => {
      active = false;
    };
  }, [api, projectId, releaseId, resourceId]);

  const loadSource = () => {
    if (source) return;
    void api
      .getDbtSource(projectId, resourceId, releaseId)
      .then((value) => {
        if (value.releaseId === releaseId) setSource(value);
      })
      .catch(() => setError('YAML 源码读取失败'));
  };
  useEffect(() => {
    if (!previewQueryId) return;
    let active = true;
    let timer: number | undefined;
    // 每次轮询独立捕获错误，并在资源切换或卸载时停止后续请求。
    const poll = async () => {
      try {
        const result = await api.getDbtQuery(projectId, previewQueryId);
        if (!active) return;
        if (result.releaseId !== releaseId) {
          setError('预览版本与请求不一致');
          return;
        }
        setPreview(result);
        if (result.status === 'PENDING' || result.status === 'RUNNING')
          timer = window.setTimeout(() => void poll(), 1000);
      } catch {
        if (active) setError('预览读取失败');
      }
    };
    void poll();
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [api, projectId, releaseId, previewQueryId]);
  const loadPreview = async () => {
    try {
      setPreview(undefined);
      const submitted = await api.previewDbtDataset(projectId, resourceId, releaseId, 20);
      if (submitted.releaseId !== releaseId) {
        setError('预览版本与请求不一致');
        return;
      }
      setPreviewQueryId(submitted.queryId);
    } catch {
      setError('预览读取失败');
    }
  };
  return (
    <Card title={presentation?.title ?? '资源详情'}>
      {error && <Alert type="error" title={error} />}
      {presentation && (
        <Tabs
          onChange={(key) => {
            if (key === 'source') loadSource();
          }}
          items={[
            {
              key: 'detail',
              label: '结构化详情',
              children: (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Typography.Text>类型：{presentation.kind}</Typography.Text>
                  {presentation.sections.map((section) => (
                    <Card key={section.title} size="small" title={section.title}>
                      <Descriptions
                        column={1}
                        items={Object.entries(section.fields).map(([key, value]) => ({
                          key,
                          label: key,
                          children: (
                            <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>
                              {text(value)}
                            </Typography.Text>
                          ),
                        }))}
                      />
                    </Card>
                  ))}
                  <Card size="small" title="原生属性">
                    <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                      {JSON.stringify(presentation.nativeDetails, null, 2)}
                    </pre>
                  </Card>
                  {presentation.queryable && physicalKinds.has(presentation.kind) && (
                    <Button onClick={() => void loadPreview()}>预览数据</Button>
                  )}
                  {preview && <DbtQueryResults result={preview} />}
                </Space>
              ),
            },
            {
              key: 'lineage',
              label: '血缘',
              children: lineage && <DbtLineageGraph lineage={lineage} onSelect={onSelect} />,
            },
            {
              key: 'source',
              label: 'YAML 源码',
              children: source ? (
                <>
                  <Typography.Text>来源提交：{source.buildSourceSha}</Typography.Text>
                  <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {source.content}
                  </pre>
                </>
              ) : (
                <Typography.Text>读取中…</Typography.Text>
              ),
            },
          ]}
        />
      )}
    </Card>
  );
}
