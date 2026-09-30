import type { DbtLineage } from '../../api/types';

export const NODE_WIDTH = 220;
export const NODE_HEIGHT = 88;
const PADDING = 40;
const COLUMN_GAP = 96;
const ROW_GAP = 40;

/** 拓扑层级提供稳定间距；异常环上的剩余节点独占行，仍可检查其关系。 */
export function layoutLineage(lineage: DbtLineage) {
  const ids = new Set([lineage.resourceId]);
  lineage.dependencies.forEach((edge) => {
    ids.add(edge.upstreamResourceId);
    ids.add(edge.downstreamResourceId);
  });
  const ordered = [...ids].sort();
  const children = new Map(ordered.map((id) => [id, new Set<string>()]));
  const incoming = new Map(ordered.map((id) => [id, 0]));
  const ranks = new Map(ordered.map((id) => [id, 0]));
  lineage.dependencies.forEach(({ upstreamResourceId: from, downstreamResourceId: to }) => {
    if (!children.get(from)!.has(to)) {
      children.get(from)!.add(to);
      incoming.set(to, incoming.get(to)! + 1);
    }
  });
  const queue = ordered.filter((id) => incoming.get(id) === 0);
  const visited = new Set<string>();
  for (let index = 0; index < queue.length; index++) {
    const id = queue[index];
    visited.add(id);
    for (const child of children.get(id)!) {
      ranks.set(child, Math.max(ranks.get(child)!, ranks.get(id)! + 1));
      incoming.set(child, incoming.get(child)! - 1);
      if (incoming.get(child) === 0) queue.push(child);
    }
  }
  const fallbackRank = Math.max(...ranks.values(), 0) + 1;
  ordered.filter((id) => !visited.has(id)).forEach((id) => ranks.set(id, fallbackRank));
  const columns = new Map<number, string[]>();
  ordered.forEach((id) => {
    const rank = ranks.get(id)!;
    columns.set(rank, [...(columns.get(rank) ?? []), id]);
  });
  const rows = Math.max(...[...columns.values()].map((column) => column.length));
  const height = PADDING * 2 + rows * (NODE_HEIGHT + ROW_GAP) - ROW_GAP;
  const nodes = [...columns.entries()].flatMap(([rank, column]) =>
    column.map((id, row) => ({
      id,
      x: PADDING + rank * (NODE_WIDTH + COLUMN_GAP),
      y:
        (height - (column.length * (NODE_HEIGHT + ROW_GAP) - ROW_GAP)) / 2 +
        row * (NODE_HEIGHT + ROW_GAP),
    })),
  );
  return {
    nodes,
    width: PADDING * 2 + (Math.max(...columns.keys()) + 1) * (NODE_WIDTH + COLUMN_GAP) - COLUMN_GAP,
    height,
    hasCycle: visited.size !== ordered.length,
  };
}
