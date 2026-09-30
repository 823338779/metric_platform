import {
  AimOutlined,
  MinusOutlined,
  PlusOutlined,
  ExpandOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons';
import { Button, Space, Tooltip } from 'antd';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { DbtLineage } from '../../api/types';
import { layoutLineage, NODE_HEIGHT, NODE_WIDTH } from './lineageLayout';
import { RESOURCE_LABELS, resourceName } from './resourceDisplay';
import './resource-workspace.css';

const COPY = {
  label: '资源血缘图',
  fit: '适应画布',
  focus: '定位当前资源',
  zoomIn: '放大',
  zoomOut: '缩小',
  open: '查看资源',
  empty: '暂无血缘关系',
  hint: '沿箭头查看数据流向 · 拖动画布平移，点击节点查看详情',
  cycle: '存在循环依赖，相关节点已展开供检查',
  current: '当前资源',
};
const MIN_ZOOM = 0.4,
  MAX_ZOOM = 1.6,
  ZOOM_STEP = 0.2;
const FIT_MIN_ZOOM = 0.8;
const DOT = '.';

export function DbtLineageGraph({
  lineage,
  onSelect,
}: {
  lineage: DbtLineage;
  onSelect: (resourceId: string) => void;
}) {
  const layout = useMemo(() => layoutLineage(lineage), [lineage]);
  const [selected, setSelected] = useState(lineage.resourceId);
  const [zoom, setZoom] = useState(1);
  const viewport = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; left: number; top: number } | undefined>(undefined);
  const marker = useId().replaceAll(':', '');
  const positions = new Map(layout.nodes.map((node) => [node.id, node]));
  const fit = (readable = false) => {
    const element = viewport.current;
    if (!element) return;
    setZoom(
      Math.max(
        readable ? FIT_MIN_ZOOM : 0.05,
        Math.min(
          1,
          (element.clientWidth - 24) / layout.width,
          (element.clientHeight - 24) / layout.height,
        ),
      ),
    );
    element.scrollLeft = 0;
    element.scrollTop = 0;
  };
  useEffect(() => {
    setSelected(lineage.resourceId);
    fit(true);
    if (!viewport.current || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => fit(true));
    observer.observe(viewport.current);
    return () => observer.disconnect();
  }, [layout, lineage.resourceId]);
  const focus = () => {
    setSelected(lineage.resourceId);
    const node = positions.get(lineage.resourceId)!;
    if (viewport.current) {
      viewport.current.scrollLeft =
        (node.x + NODE_WIDTH / 2) * zoom - viewport.current.clientWidth / 2;
      viewport.current.scrollTop =
        (node.y + NODE_HEIGHT / 2) * zoom - viewport.current.clientHeight / 2;
    }
  };
  return (
    <section className="lineage-workspace" aria-label={COPY.label}>
      <div className="resource-toolbar">
        <span className="resource-muted">
          {layout.nodes.length} 个节点 · {lineage.dependencies.length} 条依赖
        </span>
        <Space size={4} wrap>
          <Tooltip title={COPY.zoomOut}>
            <Button
              aria-label={COPY.zoomOut}
              icon={<MinusOutlined />}
              disabled={zoom <= MIN_ZOOM}
              onClick={() => setZoom((value) => Math.max(MIN_ZOOM, value - ZOOM_STEP))}
            />
          </Tooltip>
          <span className="lineage-zoom">{Math.round(zoom * 100)}%</span>
          <Tooltip title={COPY.zoomIn}>
            <Button
              aria-label={COPY.zoomIn}
              icon={<PlusOutlined />}
              disabled={zoom >= MAX_ZOOM}
              onClick={() => setZoom((value) => Math.min(MAX_ZOOM, value + ZOOM_STEP))}
            />
          </Tooltip>
          <Button icon={<ExpandOutlined aria-hidden />} onClick={() => fit()}>
            {COPY.fit}
          </Button>
          <Button icon={<AimOutlined aria-hidden />} onClick={focus}>
            {COPY.focus}
          </Button>
        </Space>
      </div>
      <div
        className="lineage-viewport"
        ref={viewport}
        onPointerDown={(event) => {
          if (event.button !== 0 || (event.target as HTMLElement).closest('button')) return;
          const element = event.currentTarget;
          drag.current = {
            x: event.clientX,
            y: event.clientY,
            left: element.scrollLeft,
            top: element.scrollTop,
          };
          element.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          if (!drag.current) return;
          event.currentTarget.scrollLeft = drag.current.left - (event.clientX - drag.current.x);
          event.currentTarget.scrollTop = drag.current.top - (event.clientY - drag.current.y);
        }}
        onPointerUp={() => {
          drag.current = undefined;
        }}
        onPointerCancel={() => {
          drag.current = undefined;
        }}
      >
        <div
          className="lineage-scroll-area"
          style={{ width: layout.width * zoom, height: layout.height * zoom }}
        >
          <div
            className="lineage-stage"
            style={{ width: layout.width, height: layout.height, transform: `scale(${zoom})` }}
          >
            <svg
              className="lineage-edges"
              width={layout.width}
              height={layout.height}
              aria-hidden="true"
            >
              <defs>
                <marker
                  id={marker}
                  viewBox="0 0 10 10"
                  refX="9"
                  refY="5"
                  markerWidth="7"
                  markerHeight="7"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" />
                </marker>
              </defs>
              {lineage.dependencies.map((edge, index) => {
                const from = positions.get(edge.upstreamResourceId)!;
                const to = positions.get(edge.downstreamResourceId)!;
                const x1 = from.x + NODE_WIDTH,
                  y1 = from.y + NODE_HEIGHT / 2;
                const x2 = to.x,
                  y2 = to.y + NODE_HEIGHT / 2;
                const path =
                  x2 > x1
                    ? `M ${x1} ${y1} C ${(x1 + x2) / 2} ${y1}, ${(x1 + x2) / 2} ${y2}, ${x2 - 3} ${y2}`
                    : `M ${x1} ${y1} C ${x1 + 32} ${y1}, ${x1 + 32} ${from.y - 20}, ${from.x + NODE_WIDTH / 2} ${from.y - 20} S ${x2 - 28} ${to.y - 20}, ${x2 - 3} ${y2}`;
                return (
                  <path
                    key={index}
                    d={path}
                    className={
                      edge.upstreamResourceId === selected || edge.downstreamResourceId === selected
                        ? 'lineage-edge is-selected'
                        : 'lineage-edge'
                    }
                    markerEnd={`url(#${marker})`}
                  >
                    <title>{edge.kind}</title>
                  </path>
                );
              })}
            </svg>
            {layout.nodes.map((node) => (
              <button
                type="button"
                key={node.id}
                className="lineage-node"
                aria-label={node.id}
                title={node.id}
                aria-pressed={selected === node.id}
                style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                onClick={() => setSelected(node.id)}
              >
                <span className="lineage-kind">
                  {RESOURCE_LABELS[node.id.split(DOT)[0].toUpperCase()] ?? node.id.split(DOT)[0]}
                </span>
                <strong>{resourceName(node.id)}</strong>
                {node.id === lineage.resourceId && (
                  <span className="lineage-current">{COPY.current}</span>
                )}
              </button>
            ))}
          </div>
        </div>
      </div>
      <div className="lineage-inspector">
        <div aria-live="polite">
          <strong>{resourceName(selected)}</strong>
          <div className="resource-id">{selected}</div>
        </div>
        <Button icon={<ArrowRightOutlined aria-hidden />} onClick={() => onSelect(selected)}>
          {COPY.open}
        </Button>
      </div>
      <div className="resource-muted">
        {!lineage.dependencies.length ? COPY.empty : layout.hasCycle ? COPY.cycle : COPY.hint}
      </div>
    </section>
  );
}
