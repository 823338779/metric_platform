import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { DbtResource, DbtResourcePresentation } from '../../api/types';
import { ResourceTypeSummary } from './ResourceTypeSummary';

afterEach(cleanup);

it('ignores late source metadata after switching to another resource', async () => {
  let finish!: (value: DbtResource) => void;
  const api = {
    getDbtResource: vi.fn().mockReturnValue(
      new Promise<DbtResource>((resolve) => {
        finish = resolve;
      }),
    ),
    getDbtPresentation: vi.fn().mockResolvedValue({
      releaseId: 'release',
      resourceId: 'model',
      kind: 'TABLE',
      sections: [{ title: '物理关系', fields: { columns: { brand: { type: 'VARCHAR' } } } }],
    }),
  };
  const presentation: DbtResourcePresentation = {
    releaseId: 'release',
    resourceId: 'brand',
    kind: 'DIMENSION',
    title: 'brand',
    queryable: true,
    sections: [],
    nativeDetails: { name: 'brand' },
  };
  const lineage = {
    releaseId: 'release',
    resourceId: 'brand',
    dependencies: [
      { upstreamResourceId: 'semantic', downstreamResourceId: 'brand', kind: 'SEMANTIC_DIMENSION' },
    ],
  };
  const view = render(
    <ResourceTypeSummary
      presentation={presentation}
      lineage={lineage}
      projectId="shop"
      api={api as never}
    />,
  );
  await waitFor(() => expect(api.getDbtResource).toHaveBeenCalled());
  view.rerender(
    <ResourceTypeSummary
      presentation={{
        ...presentation,
        resourceId: 'amount',
        nativeDetails: { data_type: 'DECIMAL(18,2)' },
      }}
      projectId="shop"
      api={api as never}
    />,
  );
  await screen.findByText('DECIMAL(18,2)');
  await act(async () =>
    finish({
      resourceId: 'semantic',
      nativeId: 'semantic',
      kind: 'SEMANTIC_MODEL',
      name: 'products',
      queryable: false,
      definitionJson: '{"depends_on":{"nodes":["model"]}}',
    }),
  );
  expect(screen.queryByText('VARCHAR')).toBeNull();
  expect(screen.getByText('DECIMAL(18,2)')).toBeTruthy();
});
