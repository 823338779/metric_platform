import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { DbtLineageGraph } from './DbtLineageGraph';

afterEach(cleanup);

const METRIC = 'metric.shop.units_sold';
const MODEL = 'model.shop.fct_order_items';
const SEMANTIC = 'semantic_model.shop.order_items';
const edge = (upstreamResourceId: string, downstreamResourceId: string) => ({
  upstreamResourceId,
  downstreamResourceId,
  kind: 'depends_on',
});

it('separates branching dependencies into layers and opens the selected resource by its full id', () => {
  const onSelect = vi.fn();
  const { container } = render(
    <DbtLineageGraph
      lineage={{
        releaseId: 'v1',
        resourceId: METRIC,
        dependencies: [
          edge(MODEL, SEMANTIC),
          edge('model.shop.returns', SEMANTIC),
          edge(SEMANTIC, METRIC),
        ],
      }}
      onSelect={onSelect}
    />,
  );
  const nodes = Array.from(container.querySelectorAll<HTMLButtonElement>('.lineage-node'));
  expect(nodes).toHaveLength(4);
  const positions = nodes.map((node) => [
    Number.parseFloat(node.style.left),
    Number.parseFloat(node.style.top),
  ]);
  for (let i = 0; i < positions.length; i++) {
    for (let j = i + 1; j < positions.length; j++) {
      expect(
        Math.abs(positions[i][0] - positions[j][0]) >= 220 ||
          Math.abs(positions[i][1] - positions[j][1]) >= 88,
      ).toBe(true);
    }
  }
  fireEvent.click(screen.getByRole('button', { name: MODEL }));
  fireEvent.click(screen.getByRole('button', { name: '查看资源' }));
  expect(onSelect).toHaveBeenCalledWith(MODEL);
});

it('keeps cyclic and disconnected nodes selectable without collapsing them', () => {
  render(
    <DbtLineageGraph
      lineage={{
        releaseId: 'v1',
        resourceId: METRIC,
        dependencies: [
          edge(MODEL, SEMANTIC),
          edge(SEMANTIC, MODEL),
          edge('model.shop.a', 'model.shop.b'),
        ],
      }}
      onSelect={vi.fn()}
    />,
  );
  expect(screen.getByRole('button', { name: METRIC })).toBeTruthy();
  expect(screen.getByRole('button', { name: MODEL })).toBeTruthy();
  expect(screen.getByRole('button', { name: SEMANTIC })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'model.shop.b' })).toBeTruthy();
});

it('shows an isolated resource even when it has no dependencies', () => {
  render(
    <DbtLineageGraph
      lineage={{ releaseId: 'v1', resourceId: METRIC, dependencies: [] }}
      onSelect={vi.fn()}
    />,
  );
  expect(screen.getByRole('button', { name: METRIC })).toBeTruthy();
  expect(screen.getByText('暂无血缘关系')).toBeTruthy();
});
