import { Alert, Button, Skeleton, Space, Tabs, Tag, Typography } from 'antd';
import {
  ApartmentOutlined,
  CodeOutlined,
  FileTextOutlined,
  PlayCircleOutlined,
  FunctionOutlined,
  TableOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { ApiProblem } from '../../api/httpClient';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type {
  DbtLineage,
  DbtQueryStatus,
  DbtResource,
  DbtResourcePresentation,
  DbtSourceDocument,
} from '../../api/types';
import { DbtLineageGraph } from './DbtLineageGraph';
import { DbtQueryResults } from './DbtQueryResults';
import { ResourceFields, RESOURCE_LABELS, resourceName } from './resourceDisplay';
import { ResourceSource } from './ResourceSource';
import { ResourceTypeSummary } from './ResourceTypeSummary';
import { LineageSummary } from './LineageSummary';
import type { ResourceDataType } from './resourceDataType';
import { hasSerializedNode, restoreArtifactPresentation } from './resourcePresentation';
import './resource-workspace.css';

type DetailApi = Pick<
  MetricPlatformApi,
  'getDbtPresentation' | 'getDbtResource' | 'getDbtSource' | 'getDbtLineage' | 'previewDbtDataset'
>;

const physicalKinds = new Set(['TABLE', 'VIEW', 'MODEL']);
const DETAIL = 'detail';
const LINEAGE = 'lineage';
const SOURCE = 'source';
const COPY = {
  title: '资源详情',
  detail: '概览',
  lineage: '血缘',
  source: 'YAML 源码',
  native: '原生属性',
  preview: '预览数据',
  retry: '重试',
  pinned: '定义、血缘与源码固定于同一发布版本',
  sourceLoading: '读取源码中…',
  close: '关闭资源',
};

/** 同一详情内的产物、源码和血缘均固定在目录 buildId。 */
export function DbtResourceDetail({
  projectId,
  buildId,
  resourceId,
  tabTitle,
  catalogSummary,
  onSelect,
  onExplore,
  onClose,
  api = metricPlatformApi,
}: {
  projectId: string;
  buildId: string;
  resourceId: string;
  /** 目录中的可读资源名称，避免将不透明身份显示为编辑器标签。 */
  tabTitle?: string;
  /** 只有目录元数据时仍使用标准详情布局，源码和血缘需要独立读取权限。 */
  catalogSummary?: DbtResource;
  onSelect: (resourceId: string) => void;
  onExplore?: (name: string) => void;
  onClose?: () => void;
  api?: DetailApi;
}) {
  const [presentation, setPresentation] = useState<DbtResourcePresentation>();
  const [resolvedType, setResolvedType] = useState<ResourceDataType>();
  const [source, setSource] = useState<DbtSourceDocument>();
  const [lineage, setLineage] = useState<DbtLineage>();
  const [preview, setPreview] = useState<DbtQueryStatus>();
  /** 预览请求代数；切换资源后丢弃旧响应。 */
  const previewGeneration = useRef(0);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [error, setError] = useState('');
  const [tab, setTab] = useState(DETAIL);
  const [lineageError, setLineageError] = useState('');
  const [sourceError, setSourceError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setPresentation(undefined);
    setResolvedType(undefined);
    setSource(undefined);
    setLineage(undefined);
    previewGeneration.current++;
    setPreviewBusy(false);
    setPreview(undefined);
    setError('');
    setLineageError('');
    setSourceError('');
    if (catalogSummary) {
      const fields = {
        name: catalogSummary.name,
        resourceId: catalogSummary.resourceId,
        ...(catalogSummary.description && { description: catalogSummary.description }),
        ...(catalogSummary.sourcePath &&
          (catalogSummary.kind === 'DIMENSION'
            ? { semanticModelName: catalogSummary.sourcePath }
            : { sourcePath: catalogSummary.sourcePath })),
      };
      setPresentation({
        buildId,
        resourceId,
        kind: catalogSummary.kind,
        title: catalogSummary.name,
        queryable: catalogSummary.granted === true && catalogSummary.queryable,
        nativeDetails: {},
        sections: [{ title: RESOURCE_LABELS[catalogSummary.kind] ?? catalogSummary.kind, fields }],
      });
      return () => {
        previewGeneration.current++;
      };
    }
    void api
      .getDbtPresentation(projectId, resourceId, buildId)
      .then(async (value) => {
        if (!active) return;
        if (value.buildId !== buildId) {
          setError('资源详情版本与请求不一致');
          return;
        }
        const restored = hasSerializedNode(value)
          ? restoreArtifactPresentation(
              value,
              await api.getDbtResource(projectId, resourceId, buildId),
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
      .getDbtLineage(projectId, resourceId, buildId)
      .then((value) => {
        if (active && value.buildId === buildId) setLineage(value);
        else if (active) setLineageError('血缘版本与请求不一致');
      })
      .catch(() => {
        if (active) setLineageError('血缘读取失败，请重试');
      });
    return () => {
      active = false;
      previewGeneration.current++;
    };
  }, [api, projectId, buildId, resourceId, catalogSummary, retry]);

  useEffect(() => {
    if (tab !== SOURCE || catalogSummary) return;
    let active = true;
    setSource(undefined);
    setSourceError('');
    void api
      .getDbtSource(projectId, resourceId, buildId)
      .then((value) => {
        if (!active) return;
        if (value.buildId === buildId) setSource(value);
        else setSourceError('源码版本与请求不一致');
      })
      .catch(() => {
        if (active) setSourceError('YAML 源码读取失败');
      });
    return () => {
      active = false;
    };
  }, [api, projectId, buildId, resourceId, tab, catalogSummary, retry]);

  const loadPreview = async () => {
    setTab(DETAIL);
    const generation = previewGeneration.current;
    setPreviewBusy(true);
    try {
      setPreview(undefined);
      const submitted = await api.previewDbtDataset(projectId, resourceId, buildId, 20);
      if (generation !== previewGeneration.current) return;
      if (submitted.buildId !== buildId) {
        setError('预览版本与请求不一致');
        return;
      }
      setPreview(submitted);
    } catch (cause) {
      if (generation === previewGeneration.current)
        setError(cause instanceof ApiProblem ? cause.message : '预览读取失败');
    } finally {
      if (generation === previewGeneration.current) setPreviewBusy(false);
    }
  };
  return (
    <section className="resource-detail" aria-label={COPY.title}>
      <div className="resource-editor-tabs">
        <span>
          <FileTextOutlined /> {tabTitle ?? resourceName(resourceId)}
        </span>
        {onClose && (
          <Button
            type="text"
            size="small"
            aria-label={COPY.close}
            onClick={onClose}
            icon={<CloseOutlined aria-hidden />}
          />
        )}
      </div>
      <header className="resource-detail-heading">
        <div className="resource-breadcrumb">
          {projectId && (
            <>
              {projectId} <span>/</span>{' '}
            </>
          )}
          {presentation && RESOURCE_LABELS[presentation.kind]}
        </div>
        <div className="resource-heading-content">
          <div>
            {presentation && physicalKinds.has(presentation.kind) ? (
              <TableOutlined className="resource-heading-icon" aria-hidden />
            ) : (
              <FunctionOutlined className="resource-heading-icon" aria-hidden />
            )}
            <div className="resource-title-line">
              <Typography.Title level={3}>{presentation?.title ?? COPY.title}</Typography.Title>
              {presentation && (
                <Tag color="blue">{RESOURCE_LABELS[presentation.kind] ?? presentation.kind}</Tag>
              )}
              {catalogSummary?.granted === false && <Tag color="red">无权限</Tag>}
            </div>
            <p className="resource-description">
              {(presentation?.sections
                .map((section) => section.fields.description)
                .find((value) => typeof value === 'string') as string) ?? ''}
            </p>
          </div>
          <Space>
            {presentation?.queryable && physicalKinds.has(presentation.kind) && (
              <Button
                type="primary"
                loading={previewBusy}
                disabled={previewBusy}
                onClick={() => void loadPreview()}
              >
                {COPY.preview}
              </Button>
            )}
            {presentation?.kind === 'METRIC' && presentation.queryable && onExplore && (
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={() => onExplore(presentation.title)}
              >
                开始分析
              </Button>
            )}
            {!catalogSummary && (
              <Button icon={<CodeOutlined />} onClick={() => setTab(SOURCE)}>
                查看源码
              </Button>
            )}
          </Space>
        </div>
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
                  <div className="resource-definition">
                    <h4>基本信息</h4>
                    <div className="resource-definition-grid">
                      {(presentation.kind === 'METRIC' || presentation.kind === 'DIMENSION') && (
                        <ResourceTypeSummary
                          presentation={presentation}
                          lineage={lineage}
                          projectId={projectId}
                          api={api}
                          onResolve={setResolvedType}
                        />
                      )}
                      {presentation.sections.map((section) => (
                        <section className="resource-section" key={section.title}>
                          <h4>{section.title}</h4>
                          <ResourceFields
                            fields={Object.fromEntries(
                              Object.entries(section.fields)
                                .filter(([key]) => key !== 'description')
                                .map(([key, value]) => [
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
                    </div>
                  </div>
                  {lineage && (
                    <LineageSummary
                      dataType={resolvedType}
                      lineage={lineage}
                      onSelect={onSelect}
                      onOpen={() => setTab(LINEAGE)}
                    />
                  )}
                  {lineageError && tab === DETAIL && (
                    <Alert
                      type="warning"
                      title={lineageError}
                      action={
                        <Button onClick={() => setRetry((value) => value + 1)}>{COPY.retry}</Button>
                      }
                    />
                  )}
                  {!catalogSummary && (
                    <details className="resource-native">
                      <summary>{COPY.native}</summary>
                      <Typography.Text className="resource-id" copyable>
                        {resourceId}
                      </Typography.Text>
                      <pre>{JSON.stringify(presentation.nativeDetails, null, 2)}</pre>
                    </details>
                  )}
                  {preview && <DbtQueryResults result={preview} />}
                </div>
              ),
            },
            {
              disabled: !!catalogSummary,
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
              disabled: !!catalogSummary,
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
      <footer className="resource-detail-footer">
        {catalogSummary ? '资源信息来自当前固定构建' : COPY.pinned}
      </footer>
    </section>
  );
}
