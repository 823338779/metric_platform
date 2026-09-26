import { useEffect, useRef } from 'react';
import * as echarts from 'echarts/core';
import { GraphChart } from 'echarts/charts';
import { CanvasRenderer } from 'echarts/renderers';
import type { DbtLineage } from '../../api/types';

echarts.use([GraphChart, CanvasRenderer]);

export function DbtLineageGraph({
  lineage,
  onSelect,
}: {
  lineage: DbtLineage;
  onSelect: (resourceId: string) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!container.current || !lineage.dependencies.length) return;
    const chart = echarts.init(container.current);
    const ids = new Set<string>([lineage.resourceId]);
    lineage.dependencies.forEach((edge) => {
      ids.add(edge.upstreamResourceId);
      ids.add(edge.downstreamResourceId);
    });
    chart.setOption({
      tooltip: {},
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          label: { show: true },
          data: [...ids].map((id) => ({
            id,
            name: id,
            symbolSize: id === lineage.resourceId ? 45 : 30,
          })),
          links: lineage.dependencies.map((edge) => ({
            source: edge.upstreamResourceId,
            target: edge.downstreamResourceId,
            value: edge.kind,
          })),
        },
      ],
    });
    chart.on('click', (event) => {
      if (event.dataType === 'node') onSelect(String(event.name));
    });
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      chart.dispose();
    };
  }, [lineage, onSelect]);
  if (!lineage.dependencies.length) return <p>暂无血缘关系</p>;
  return (
    <div
      ref={container}
      role="img"
      aria-label="资源血缘图"
      style={{ width: '100%', height: 320 }}
    />
  );
}
