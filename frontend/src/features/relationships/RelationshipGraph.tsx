import { Background, Controls, MiniMap, ReactFlow, type Edge, type Node } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { Dataset, DatasetRelationship } from '../../api/types';
export function RelationshipGraph({
  datasets,
  relationships,
  onSelect,
}: {
  datasets: Dataset[];
  relationships: DatasetRelationship[];
  onSelect?: (v: DatasetRelationship) => void;
}) {
  const nodes: Node[] = datasets.map((d, i) => ({
    id: d.id,
    position: { x: (i % 3) * 240, y: Math.floor(i / 3) * 140 },
    data: { label: d.name },
  }));
  const edges: Edge[] = relationships.map((r) => ({
    id: r.id,
    source: r.sourceDatasetId,
    target: r.targetDatasetId,
    label: `${r.cardinality} · LEFT JOIN`,
    animated: r.validationStatus !== 'VALID',
  }));
  return (
    <div style={{ height: 420, border: '1px solid #e5e7eb', borderRadius: 8 }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        onEdgeClick={(_, edge) => {
          const found = relationships.find((r) => r.id === edge.id);
          if (found) onSelect?.(found);
        }}
      >
        <Background />
        <Controls />
        <MiniMap />
      </ReactFlow>
    </div>
  );
}
