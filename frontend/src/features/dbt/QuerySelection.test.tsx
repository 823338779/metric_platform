import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { QuerySelection } from './QuerySelection';

afterEach(cleanup);
beforeAll(() => {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

const choices = [
  { value: 'date-id', label: '下单时间', dataType: 'DATE', granularities: ['day', 'month'] },
  { value: 'region-id', label: '地区', dataType: 'VARCHAR(64)' },
  { value: 'unknown-id', label: '未知维度' },
];

function Selection() {
  const [selected, setSelected] = useState<string[]>([]);
  const [grains, setGrains] = useState<Record<string, string>>({});
  return (
    <QuerySelection
      label="分组维度"
      options={choices}
      selected={selected}
      onChange={setSelected}
      grains={grains}
      onGrainChange={(id, grain) => setGrains((value) => ({ ...value, [id]: grain }))}
    />
  );
}

it('shows declared types and edits time grain inside a removable selected tag', async () => {
  render(<Selection />);
  expect(screen.getByText('DATE')).toBeTruthy();
  expect(screen.getByRole('button', { name: '下单时间', description: 'DATE' })).toBeTruthy();
  expect(screen.getByText('VARCHAR(64)')).toBeTruthy();
  expect(screen.getByText('类型待确认')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '添加分组维度' }));
  expect(screen.queryByRole('region', { name: '选择分组维度' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '添加分组维度' }));
  fireEvent.click(screen.getByRole('button', { name: '下单时间' }));
  fireEvent.click(screen.getByRole('button', { name: '下单时间时间粒度' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '月' }));
  expect(screen.getByRole('button', { name: '下单时间时间粒度' }).textContent).toContain('月');
  expect(
    screen.getByRole('button', { name: '下单时间时间粒度', description: '下单时间：月' }),
  ).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '移除分组维度 下单时间' }));
  expect(screen.queryByRole('button', { name: '下单时间时间粒度' })).toBeNull();
});

it('searches additional dimensions and preserves their opaque identities', async () => {
  const change = vi.fn();
  render(
    <QuerySelection label="分组维度" options={choices} selected={['date-id']} onChange={change} />,
  );
  fireEvent.click(screen.getByRole('button', { name: '添加分组维度' }));
  fireEvent.change(await screen.findByRole('textbox', { name: '搜索分组维度' }), {
    target: { value: '地区' },
  });
  const picker = screen.getByRole('region', { name: '选择分组维度' });
  expect(within(picker).queryByRole('button', { name: '未知维度' })).toBeNull();
  fireEvent.click(within(picker).getByRole('button', { name: '地区' }));
  expect(change).toHaveBeenCalledWith(['date-id', 'region-id']);
});

it('toggles order direction on the tag without removing or reordering fields', () => {
  const change = vi.fn();
  const direction = vi.fn();
  render(
    <QuerySelection
      label="排序字段"
      options={choices}
      selected={['date-id', 'region-id']}
      onChange={change}
      directions={{ 'date-id': 'ASC', 'region-id': 'DESC' }}
      onDirectionChange={direction}
      showTypes={false}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '地区排序方向：降序' }));
  expect(direction).toHaveBeenCalledWith('region-id', 'ASC');
  expect(change).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '移除排序字段 下单时间' }));
  expect(change).toHaveBeenCalledWith(['region-id']);
});
