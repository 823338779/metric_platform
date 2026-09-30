import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { ResourceFields } from './resourceDisplay';

afterEach(cleanup);

it('omits empty optional fields while retaining false and zero', () => {
  render(
    <ResourceFields
      fields={{
        description: '商品品牌',
        unset: null,
        blank: '',
        empty: [],
        nestedEmpty: { optional: null },
        enabled: false,
        precision: 0,
      }}
    />,
  );
  expect(screen.getByText('商品品牌')).toBeTruthy();
  expect(screen.getByText('否')).toBeTruthy();
  expect(screen.getByText('0')).toBeTruthy();
  for (const field of ['unset', 'blank', 'empty', 'nestedEmpty'])
    expect(screen.queryByText(field)).toBeNull();
});

it('keeps nested configuration collapsed with a useful expression summary', () => {
  const { container } = render(
    <ResourceFields
      fields={{
        type: 'simple',
        type_params: {
          expr: 'gross_amount',
          window: null,
          is_private: false,
          metric_aggregation_params: { agg: 'sum' },
        },
      }}
    />,
  );
  expect(screen.getByText('simple')).toBeTruthy();
  const details = container.querySelector('details')!;
  expect(details.open).toBe(false);
  expect(details.querySelector('summary')?.textContent).toContain('gross_amount');
  expect(details.textContent).toContain('聚合方式');
  expect(screen.queryByText('window')).toBeNull();
});

it('collapses collections rather than laying out every record on initial load', () => {
  const { container } = render(
    <ResourceFields
      fields={{
        columns: [
          { name: 'brand', type: 'text' },
          { name: 'amount', type: 'numeric' },
        ],
      }}
    />,
  );
  const details = container.querySelector('details')!;
  expect(details.open).toBe(false);
  expect(details.querySelector('summary')?.textContent).toBe('2 项');
  expect(details.textContent).toContain('brand');
});
