import { useEffect, useRef } from 'react';
import * as echarts from 'echarts/core';
import { GraphChart } from 'echarts/charts';
import { SVGRenderer } from 'echarts/renderers';
import type { ObjectType, Relation } from '../../api/ontologyApi';

echarts.use([GraphChart, SVGRenderer]);

export function OntologyGraph({
  objects,
  relations,
  onSelect,
}: {
  objects: ObjectType[];
  relations: Relation[];
  onSelect: (kind: 'object' | 'relation', id: string) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = container.current;
    if (!element || !objects.length || !element.clientWidth) return;
    const chart = echarts.init(element, undefined, { renderer: 'svg' });
    const nodes = objects.map((o, index) => {
      const angle = (2 * Math.PI * index) / objects.length;
      return {
        id: o.id,
        name: o.name,
        x: Math.round(100 * Math.cos(angle)),
        y: Math.round(70 * Math.sin(angle)),
      };
    });
    // ECharts 同节点连线长度为零，用不可见的转折点和两段弧线表示同一个具名回环。
    const anchors = nodes.flatMap((node) => {
      const loops = relations.filter(
        (r) => r.sourceObjectId === node.id && r.targetObjectId === node.id,
      );
      return loops.map((r, index) => {
        const angle = (2 * Math.PI * index) / loops.length - Math.PI / 3;
        return {
          id: 'loop-' + r.id,
          x: node.x + 35 * Math.cos(angle),
          y: node.y + 35 * Math.sin(angle),
          symbolSize: 0,
          label: { show: false },
        };
      });
    });
    const links = relations.flatMap((r) => {
      const edge = {
        id: r.id,
        relationId: r.id,
        name: r.name,
        source: r.sourceObjectId,
        target: r.targetObjectId,
      };
      if (r.sourceObjectId !== r.targetObjectId) return [edge];
      const anchor = 'loop-' + r.id;
      const lineStyle = { curveness: 0.65 };
      return [
        { ...edge, target: anchor, symbol: ['none', 'none'], lineStyle },
        { ...edge, id: 'return-' + r.id, source: anchor, label: { show: false }, lineStyle },
      ];
    });
    chart.setOption({
      animation: false,
      series: [
        {
          type: 'graph',
          layout: 'none',
          preserveAspect: 'contain',
          roam: true,
          top: '24%',
          bottom: '24%',
          left: '18%',
          right: '18%',
          symbolSize: 22,
          itemStyle: { color: '#2763a6', borderColor: '#fff', borderWidth: 2 },
          label: { show: true, position: 'bottom', color: '#1d334c', fontSize: 14 },
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: 10,
          autoCurveness: true,
          edgeLabel: {
            show: true,
            formatter: ({ data }: { data: { name: string } }) => data.name,
            fontSize: 12,
            color: '#34536c',
            backgroundColor: '#f6f9fc',
            padding: [2, 4],
          },
          lineStyle: { color: '#67849b', width: 2, opacity: 1 },
          data: [...nodes, ...anchors],
          links,
        },
      ],
    });
    chart.on('click', (event) => {
      const datum = event.data as { id: string; relationId?: string };
      if (event.dataType === 'edge' && datum.relationId) onSelect('relation', datum.relationId);
      else if (objects.some((o) => o.id === datum.id)) onSelect('object', datum.id);
    });
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(element);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [objects, relations, onSelect]);
  return <div className="ontology-graph" ref={container} role="img" aria-label="对象关系拓扑图" />;
}
