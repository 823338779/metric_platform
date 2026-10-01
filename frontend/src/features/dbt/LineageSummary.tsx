import { ApartmentOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import type { DbtLineage } from '../../api/types';
import { resourceName, RESOURCE_LABELS } from './resourceDisplay';
import type { ResourceDataType } from './resourceDataType';

const COPY = {
  title: '来源与依赖',
  open: '打开完整血缘',
  upstream: '上游资源',
  current: '当前资源',
  empty: '当前版本未声明直接上游依赖',
};

export function LineageSummary({
  lineage,
  onSelect,
  onOpen,
  dataType,
}: {
  lineage: DbtLineage;
  onSelect: (id: string) => void;
  onOpen: () => void;
  dataType?: ResourceDataType;
}) {
  const upstream = [
    ...new Set(
      lineage.dependencies
        .filter((edge) => edge.downstreamResourceId === lineage.resourceId)
        .map((edge) => edge.upstreamResourceId),
    ),
  ];
  return (
    <section className="lineage-summary">
      <div className="resource-section-heading">
        <h4>{COPY.title}</h4>
        <Button type="link" onClick={onOpen}>
          {COPY.open}
          <ArrowRightOutlined />
        </Button>
      </div>
      {upstream.length ? (
        <div className="lineage-summary-flow">
          <div className="lineage-summary-sources">
            {upstream.slice(0, 3).map((id) => (
              <button
                key={id}
                className="lineage-summary-node"
                title={id}
                onClick={() => onSelect(id)}
              >
                <span className="lineage-summary-node-head">
                  <ApartmentOutlined />
                  <span>
                    <strong>{resourceName(id)}</strong>
                    <small>
                      {RESOURCE_LABELS[id.split('.')[0].toUpperCase()] ?? COPY.upstream}
                    </small>
                  </span>
                </span>
                <span className="lineage-summary-node-foot">
                  {dataType?.source?.resourceId === id ? (
                    <>
                      {dataType.source.field}
                      <small>{dataType.source.dataType}</small>
                    </>
                  ) : (
                    COPY.upstream
                  )}
                </span>
              </button>
            ))}
            {upstream.length > 3 && (
              <Button type="link" onClick={onOpen}>
                查看全部 {upstream.length} 个上游
              </Button>
            )}
          </div>
          <ArrowRightOutlined className="lineage-summary-arrow" aria-hidden />
          <div className="lineage-summary-node is-current">
            <span className="lineage-summary-node-head">
              <ApartmentOutlined />
              <span>
                <strong>{resourceName(lineage.resourceId)}</strong>
                <small>{COPY.current}</small>
              </span>
            </span>
            <span className="lineage-summary-node-foot">{dataType?.label ?? COPY.current}</span>
          </div>
        </div>
      ) : (
        <p className="resource-muted">{COPY.empty}</p>
      )}
      {upstream.length === 1 && (
        <div className="resource-source-link">
          <span>{dataType?.source ? '来源字段' : '来源资源'}</span>
          <strong>
            {resourceName(upstream[0])}
            {dataType?.source ? `.${dataType.source.field}` : ''}
          </strong>
          <Button type="link" onClick={() => onSelect(upstream[0])}>
            查看来源 <ArrowRightOutlined />
          </Button>
        </div>
      )}
    </section>
  );
}
