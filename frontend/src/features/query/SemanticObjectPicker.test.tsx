import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import type { SemanticSearchResult } from '../../api/types';
import { SemanticObjectPicker } from './SemanticObjectPicker';

afterEach(cleanup);
beforeAll(() => {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

const addableMetric = semantic('metric-addable', 'METRIC', '可添加指标');
const selectedMetric = semantic('metric-selected', 'METRIC', '已选指标');
const disabledMetric = semantic('metric-disabled', 'METRIC', '不可用指标');
const addableDimension = semantic('dimension-addable', 'DIMENSION', '可添加维度');

it('renders compact stacked libraries with addable objects first', () => {
  render(
    <SemanticObjectPicker
      objects={[disabledMetric, selectedMetric, addableMetric, addableDimension]}
      compatibility={{
        [disabledMetric.id]: { state: 'INCOMPATIBLE', reason: 'DIFFERENT_DATA_SOURCE' },
        [selectedMetric.id]: { state: 'SELECTED' },
        [addableMetric.id]: { state: 'ADDABLE' },
        [addableDimension.id]: { state: 'ADDABLE' },
      }}
      query=""
      loading={false}
      connectivityReady
      onQuery={() => undefined}
      onAdd={() => undefined}
    />,
  );

  const metricLibrary = screen.getByLabelText('指标库');
  expect(
    within(metricLibrary)
      .getAllByRole('listitem')
      .map((row) => row.textContent),
  ).toEqual([
    expect.stringContaining(addableMetric.displayName),
    expect.stringContaining(selectedMetric.displayName),
    expect.stringContaining(disabledMetric.displayName),
  ]);
  expect(
    within(metricLibrary)
      .getByText(disabledMetric.displayName)
      .closest('[role="listitem"]')
      ?.getAttribute('aria-disabled'),
  ).toBe('true');
  expect(screen.queryByText('全部')).toBeNull();
  expect(screen.queryByText('可添加')).toBeNull();
  expect(screen.queryByText('不可添加')).toBeNull();
});

it('does not add or expose dragging for incompatible objects', () => {
  const onAdd = vi.fn();
  render(
    <SemanticObjectPicker
      objects={[disabledMetric]}
      compatibility={{
        [disabledMetric.id]: { state: 'INCOMPATIBLE', reason: 'DIFFERENT_DATA_SOURCE' },
      }}
      query=""
      loading={false}
      connectivityReady
      onQuery={() => undefined}
      onAdd={onAdd}
    />,
  );

  const row = screen.getByRole('listitem');
  expect(row.getAttribute('draggable')).toBe('false');
  fireEvent.click(screen.getByRole('button', { name: `添加${disabledMetric.displayName}` }));
  expect(onAdd).not.toHaveBeenCalled();
});

function semantic(
  id: string,
  type: 'METRIC' | 'DIMENSION',
  displayName: string,
): SemanticSearchResult {
  return {
    id,
    type,
    datasetId: `dataset-${id}`,
    displayName,
    name: id,
    aliases: [],
    score: 1,
    matchReasons: [],
    version: 1,
    summary: {},
  };
}
