import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { DbtResourceDetail } from './DbtResourceDetail';

beforeAll(() => {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  window.matchMedia = vi.fn().mockImplementation(() => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
});
afterEach(cleanup);

const presentation = (kind: string) => ({
  releaseId: 'release-1',
  resourceId: 'r',
  kind,
  title: `${kind} title`,
  queryable: true,
  sections: [{ title: kind, fields: { description: '业务口径', expr: 'amount' } }],
  nativeDetails: { custom: '<img src=x onerror=alert(1)>' },
});
const api = (kind: string) => ({
  getDbtPresentation: vi.fn().mockResolvedValue(presentation(kind)),
  getDbtLineage: vi
    .fn()
    .mockResolvedValue({ releaseId: 'release-1', resourceId: 'r', dependencies: [] }),
  getDbtSource: vi.fn().mockResolvedValue({
    releaseId: 'release-1',
    buildSourceSha: 'sha',
    sourcePath: 'models/metrics.yml',
    content: '<script>alert(1)</script>',
  }),
  previewDbtDataset: vi.fn(),
  getDbtQuery: vi.fn(),
});

it.each(['METRIC', 'DIMENSION', 'SEMANTIC_MODEL', 'TABLE', 'VIEW'])(
  'renders %s with typed sections and native details',
  async (kind) => {
    render(
      <DbtResourceDetail
        projectId="demo"
        releaseId="release-1"
        resourceId="r"
        onSelect={vi.fn()}
        api={api(kind) as never}
      />,
    );
    expect(await screen.findByText(`${kind} title`)).toBeTruthy();
    expect(screen.getByText('业务口径')).toBeTruthy();
    expect(screen.getByText(/<img src=x onerror=alert/)).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'x' })).toBeNull();
  },
);

it('shows YAML as inert text from the same release', async () => {
  const client = api('METRIC');
  render(
    <DbtResourceDetail
      projectId="demo"
      releaseId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('METRIC title');
  fireEvent.click(screen.getByRole('tab', { name: 'YAML 源码' }));
  expect(await screen.findByText('<script>alert(1)</script>')).toBeTruthy();
  expect(document.querySelector('script[src="x"]')).toBeNull();
  expect(client.getDbtSource).toHaveBeenCalledWith('demo', 'r', 'release-1');
});

it('hides preview for a non-queryable relation', async () => {
  const client = api('TABLE');
  client.getDbtPresentation.mockResolvedValue({ ...presentation('TABLE'), queryable: false });
  render(
    <DbtResourceDetail
      projectId="demo"
      releaseId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('TABLE title');
  expect(screen.queryByRole('button', { name: '预览数据' })).toBeNull();
});

it('reports a later preview poll failure and stops after leaving detail', async () => {
  const client = api('TABLE');
  client.previewDbtDataset.mockResolvedValue({ queryId: 'q', releaseId: 'release-1' });
  client.getDbtQuery
    .mockResolvedValueOnce({ queryId: 'q', releaseId: 'release-1', status: 'RUNNING' })
    .mockRejectedValueOnce(new Error('network'));
  const view = render(
    <DbtResourceDetail
      projectId="demo"
      releaseId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('TABLE title');
  fireEvent.click(screen.getByRole('button', { name: '预览数据' }));
  expect(await screen.findByText('预览读取失败', {}, { timeout: 3000 })).toBeTruthy();
  const calls = client.getDbtQuery.mock.calls.length;
  view.unmount();
  await new Promise((resolve) => setTimeout(resolve, 1100));
  expect(client.getDbtQuery).toHaveBeenCalledTimes(calls);
});
