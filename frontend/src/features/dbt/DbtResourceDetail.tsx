import { Alert, Button, Skeleton, Space, Tabs, Tag, Typography } from 'antd';
import { ApartmentOutlined, CodeOutlined, FileTextOutlined } from '@ant-design/icons';
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
import { ResourceFields, RESOURCE_LABELS } from './resourceDisplay';
import { ResourceSource } from './ResourceSource';
import { ResourceTypeSummary } from './ResourceTypeSummary';
import { hasSerializedNode, restoreArtifactPresentation } from './resourcePresentation';
import './resource-workspace.css';

type DetailApi = Pick<
  MetricPlatformApi,
  | 'getDbtPresentation'
  | 'getDbtResource'
  | 'getDbtSource'
  | 'getDbtLineage'
  | 'previewDbtDataset'
  | 'getDbtQuery'
>;

const physicalKinds = new Set(['TABLE', 'VIEW', 'MODEL']);
const DETAIL = 'detail';
const LINEAGE = 'lineage';
const SOURCE = 'source';
const COPY = {
  title: '资源详情',
  detail: '结构化详情',
  lineage: '血缘',
  source: 'YAML 源码',
  native: '原生属性',
  preview: '预览数据',
  retry: '重试',
  pinned: '定义、血缘与源码固定于同一发布版本',
  sourceLoading: '读取源码中…',
};

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
  const [tab, setTab] = useState(DETAIL);
  const [lineageError, setLineageError] = useState('');
  const [sourceError, setSourceError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setPresentation(undefined);
    setSource(undefined);
    setLineage(undefined);
    setPreview(undefined);
    setPreviewQueryId('');
    setError('');
    setLineageError('');
    setSourceError('');
    void api
      .getDbtPresentation(projectId, resourceId, releaseId)
      .then(async (value) => {
        if (!active) return;
        if (value.releaseId !== releaseId) {
          setError('资源详情版本与请求不一致');
          return;
        }
        const restored = hasSerializedNode(value)
          ? restoreArtifactPresentation(
              value,
              await api.getDbtResource(projectId, resourceId, releaseId),
            )
          : value;
        if (active) setPresentation(restored);
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
        else if (active) setLineageError('血缘版本与请求不一致');
      })
      .catch(() => {
        if (active) setLineageError('血缘读取失败，请重试');
      });
    return () => {
      active = false;
    };
  }, [api, projectId, releaseId, resourceId, retry]);

  useEffect(() => {
    if (tab !== SOURCE) return;
    let active = true;
    setSource(undefined);
    setSourceError('');
    void api
      .getDbtSource(projectId, resourceId, releaseId)
      .then((value) => {
        if (!active) return;
        if (value.releaseId === releaseId) setSource(value);
        else setSourceError('源码版本与请求不一致');
      })
      .catch(() => {
        if (active) setSourceError('YAML 源码读取失败');
      });
    return () => {
      active = false;
    };
  }, [api, projectId, releaseId, resourceId, tab, retry]);
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
    <section className="resource-detail" aria-label={COPY.title}>
      <header className="resource-detail-heading">
        <div className="resource-title-line">
          <Typography.Title level={3}>{presentation?.title ?? COPY.title}</Typography.Title>
          {presentation && (
            <Tag color="blue">{RESOURCE_LABELS[presentation.kind] ?? presentation.kind}</Tag>
          )}
        </div>
        <Typography.Text className="resource-id" copyable>
          {resourceId}
        </Typography.Text>
      </header>
      {error && (
        <Alert
          type="error"
          title={error}
          action={<Button onClick={() => setRetry((value) => value + 1)}>{COPY.retry}</Button>}
        />
      )}
      {!presentation && !error && <Skeleton active className="resource-loading" />}
      {presentation && (
        <Tabs
          className="resource-detail-tabs"
          activeKey={tab}
          onChange={setTab}
          items={[
            {
              key: DETAIL,
              label: COPY.detail,
              icon: <FileTextOutlined aria-hidden />,
              children: (
                <div className="resource-overview">
                  {(presentation.kind === 'METRIC' || presentation.kind === 'DIMENSION') && (
                    <ResourceTypeSummary
                      presentation={presentation}
                      lineage={lineage}
                      projectId={projectId}
                      api={api}
                    />
                  )}
                  {presentation.sections.map((section) => (
                    <section className="resource-section" key={section.title}>
                      <h4>{section.title}</h4>
                      <ResourceFields
                        fields={Object.fromEntries(
                          Object.entries(section.fields).map(([key, value]) => [
                            key === 'type' && presentation.kind === 'METRIC'
                              ? 'metricType'
                              : key === 'type' && presentation.kind === 'DIMENSION'
                                ? 'dimensionType'
                                : key,
                            value,
                          ]),
                        )}
                      />
                    </section>
                  ))}
                  <details className="resource-native">
                    <summary>{COPY.native}</summary>
                    <pre>{JSON.stringify(presentation.nativeDetails, null, 2)}</pre>
                  </details>
                  {presentation.queryable && physicalKinds.has(presentation.kind) && (
                    <Button onClick={() => void loadPreview()}>{COPY.preview}</Button>
                  )}
                  {preview && <DbtQueryResults result={preview} />}
                </div>
              ),
            },
            {
              key: LINEAGE,
              label: COPY.lineage,
              icon: <ApartmentOutlined aria-hidden />,
              children: lineageError ? (
                <Alert
                  type="error"
                  title={lineageError}
                  action={
                    <Button onClick={() => setRetry((value) => value + 1)}>{COPY.retry}</Button>
                  }
                />
              ) : lineage ? (
                <DbtLineageGraph lineage={lineage} onSelect={onSelect} />
              ) : (
                <Skeleton active />
              ),
            },
            {
              key: SOURCE,
              label: COPY.source,
              icon: <CodeOutlined aria-hidden />,
              children: sourceError ? (
                <Alert
                  type="error"
                  title={sourceError}
                  action={
                    <Button onClick={() => setRetry((value) => value + 1)}>{COPY.retry}</Button>
                  }
                />
              ) : source ? (
                <ResourceSource
                  source={source}
                  name={presentation.title}
                  kind={presentation.kind}
                />
              ) : (
                <Space>
                  <Typography.Text type="secondary">{COPY.sourceLoading}</Typography.Text>
                </Space>
              ),
            },
          ]}
        />
      )}
      <footer className="resource-detail-footer">{COPY.pinned}</footer>
    </section>
  );
}
