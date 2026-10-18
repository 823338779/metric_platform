import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ResourceTree } from './ResourceTree';

beforeAll(() => {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});
afterEach(cleanup);

const resources = [
  {
    resourceId: 'metric.orders',
    kind: 'METRIC',
    name: 'order_count',
    sourcePath: 'models/orders.yml',
  },
  {
    resourceId: 'dimension.status',
    kind: 'DIMENSION',
    name: 'status',
    sourcePath: 'models/orders.yml',
  },
  { resourceId: 'model.unknown', kind: 'TABLE', name: 'unknown_source' },
].map((resource) => ({
  ...resource,
  nativeId: resource.resourceId,
  definitionJson: '{}',
  queryable: false,
}));

it('默认仅展开类型和资源，切换来源分组仍能选择并定位同一资源', async () => {
  const select = vi.fn();
  render(
    <MemoryRouter>
      <ResourceTree
        resources={resources}
        selected="metric.orders"
        onSelect={select}
        projectId="demo"
        buildId="A"
      />
    </MemoryRouter>,
  );
  expect(await screen.findByRole('treeitem', { name: 'order_count' })).toHaveAttribute(
    'aria-level',
    '2',
  );
  fireEvent.click(screen.getByRole('radio', { name: '按来源' }));
  expect(await screen.findByRole('treeitem', { name: /orders.yml/ })).toHaveAttribute(
    'aria-level',
    '1',
  );
  expect(screen.getByRole('treeitem', { name: 'status' })).toHaveAttribute('aria-level', '2');
  fireEvent.click(screen.getByText('status'));
  expect(select).toHaveBeenCalledWith('dimension.status');
  fireEvent.click(screen.getByRole('button', { name: '全部折叠' }));
  expect(screen.queryByRole('treeitem', { name: 'order_count' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '定位当前资源' }));
  expect(await screen.findByRole('treeitem', { name: 'order_count' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  expect(screen.getByRole('treeitem', { name: '未提供来源' })).toBeInTheDocument();
});

it('追加分页资源不会重新展开用户已折叠的分组', async () => {
  const props = { selected: '', onSelect: vi.fn(), projectId: 'demo', buildId: 'A' };
  const view = render(
    <MemoryRouter>
      <ResourceTree {...props} resources={resources} />
    </MemoryRouter>,
  );
  await screen.findByRole('treeitem', { name: 'order_count' });
  fireEvent.click(screen.getByRole('button', { name: '全部折叠' }));
  view.rerender(
    <MemoryRouter>
      <ResourceTree
        {...props}
        resources={[...resources, { ...resources[0], resourceId: 'metric.extra', name: 'extra' }]}
      />
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.queryByRole('treeitem', { name: 'extra' })).toBeNull());
  fireEvent.click(screen.getByRole('button', { name: '全部展开' }));
  expect(await screen.findByRole('treeitem', { name: 'extra' })).toBeVisible();
});
