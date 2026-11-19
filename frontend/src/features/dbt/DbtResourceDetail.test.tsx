import { MemoryRouter } from 'react-router-dom';
import { nativeResource, publishedTreeResource } from './publishedResourceAdapter';
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

it.each(['YAML 源码', '血缘'])('从%s发起预览后展示同一固定版本的结果', async (tab) => {
  const client = api('TABLE');
  client.previewDbtDataset.mockResolvedValue({
    queryId: 'preview-navigation',
    buildId: 'release-1',
    status: 'SUCCEEDED',
    mode: 'PREVIEW',
    columns: [{ name: 'value', type: 'STRING' }],
    rows: [['preview-result']],
  });
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('TABLE title');
  fireEvent.click(screen.getByRole('tab', { name: tab }));
  fireEvent.click(screen.getByRole('button', { name: '预览数据' }));
  expect(screen.getByRole('tab', { name: '概览' }).getAttribute('aria-selected')).toBe('true');
  expect(await screen.findByText('preview-result')).toBeTruthy();
  expect(client.previewDbtDataset).toHaveBeenCalledWith('demo', 'r', 'release-1', 20);
});

const presentation = (kind: string) => ({
  buildId: 'release-1',
  resourceId: 'r',
  kind,
  title: `${kind} title`,
  queryable: true,
  sections: [{ title: kind, fields: { description: '业务口径', expr: 'amount' } }],
  nativeDetails: { custom: '<img src=x onerror=alert(1)>' },
});
const api = (kind: string) => ({
  getDbtPresentation: vi.fn().mockResolvedValue(presentation(kind)),
  getDbtResource: vi.fn(),
  getDbtLineage: vi
    .fn()
    .mockResolvedValue({ buildId: 'release-1', resourceId: 'r', dependencies: [] }),
  getDbtSource: vi.fn().mockResolvedValue({
    buildId: 'release-1',
    buildSourceSha: 'sha',
    sourcePath: 'models/metrics.yml',
    content: '<script>alert(1)</script>',
  }),
  previewDbtDataset: vi.fn(),
  getDbtQuery: vi.fn(),
});

it('recovers a broken presentation from the same release without rendering serializer flags', async () => {
  const client = api('DIMENSION');
  const brokenNode = {
    nodeType: 'STRING',
    valueNode: true,
    containerNode: false,
    missingNode: false,
    textual: true,
    bigDecimal: false,
  };
  client.getDbtPresentation.mockResolvedValue({
    ...presentation('DIMENSION'),
    sections: [{ title: '维度', fields: { type: brokenNode, description: brokenNode } }],
    nativeDetails: brokenNode,
  } as never);
  client.getDbtResource.mockResolvedValue({
    resourceId: 'r',
    kind: 'DIMENSION',
    definitionJson: '{"type":"categorical","description":"商品品牌"}',
  });
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  expect(await screen.findByText('categorical')).toBeTruthy();
  expect(screen.getByText('商品品牌')).toBeTruthy();
  expect(screen.queryByText('bigDecimal')).toBeNull();
  expect(client.getDbtResource).toHaveBeenCalledWith('demo', 'r', 'release-1');
});

it('does not request a fallback for a valid presentation', async () => {
  const client = api('DIMENSION');
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('业务口径');
  expect(client.getDbtResource).not.toHaveBeenCalled();
});

it('shows a dimension data type separately from its semantic category', async () => {
  const client = api('DIMENSION');
  client.getDbtPresentation.mockResolvedValue({
    ...presentation('DIMENSION'),
    nativeDetails: { data_type: 'varchar(64)' },
    sections: [{ title: '维度', fields: { type: 'categorical' } }],
  } as never);
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  expect(await screen.findByText('VARCHAR(64)')).toBeTruthy();
  expect(screen.getByText('维度类型')).toBeTruthy();
  expect(screen.getByText('categorical')).toBeTruthy();
  expect(screen.getByRole('region', { name: '数据类型' })).toBeTruthy();
});

it('shows a recoverable error instead of internal flags when the original definition is unavailable', async () => {
  const client = api('DIMENSION');
  const brokenNode = {
    nodeType: 'STRING',
    valueNode: true,
    containerNode: false,
    missingNode: false,
    textual: true,
    bigDecimal: false,
  };
  client.getDbtPresentation.mockResolvedValue({
    ...presentation('DIMENSION'),
    nativeDetails: brokenNode,
  } as never);
  client.getDbtResource.mockRejectedValue(new Error('offline'));
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  expect(await screen.findByText('资源详情读取失败')).toBeTruthy();
  expect(screen.queryByText('bigDecimal')).toBeNull();
  expect(screen.getByRole('button', { name: /重\s*试/ })).toBeTruthy();
});

it.each(['METRIC', 'DIMENSION', 'SEMANTIC_MODEL', 'TABLE', 'VIEW'])(
  'renders %s with typed sections and native details',
  async (kind) => {
    render(
      <DbtResourceDetail
        projectId="demo"
        buildId="release-1"
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
      buildId="release-1"
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

it('shows source location and reports lineage errors instead of claiming there are no dependencies', async () => {
  const client = api('METRIC');
  client.getDbtLineage.mockRejectedValue(new Error('offline'));
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('METRIC title');
  fireEvent.click(screen.getByRole('tab', { name: '血缘' }));
  expect(await screen.findByText('血缘读取失败，请重试')).toBeTruthy();
  expect(screen.queryByText('暂无血缘关系')).toBeNull();
  fireEvent.click(screen.getByRole('tab', { name: 'YAML 源码' }));
  expect(await screen.findByText('models/metrics.yml')).toBeTruthy();
});

it('hides preview for a non-queryable relation', async () => {
  const client = api('TABLE');
  client.getDbtPresentation.mockResolvedValue({ ...presentation('TABLE'), queryable: false });
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('TABLE title');
  expect(screen.queryByRole('button', { name: '预览数据' })).toBeNull();
});

it('locates the metric definition instead of a same-name measure earlier in the YAML', async () => {
  const client = api('METRIC');
  client.getDbtPresentation.mockResolvedValue({ ...presentation('METRIC'), title: 'units_sold' });
  client.getDbtSource.mockResolvedValue({
    buildId: 'release-1',
    buildSourceSha: 'sha',
    sourcePath: 'models/metrics.yml',
    content:
      'semantic_models:\n  - name: orders\n    measures:\n      - name: units_sold\nmetrics:\n  - name: units_sold\n    type: simple',
  });
  const { container } = render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="metric.units_sold"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('units_sold');
  fireEvent.click(screen.getByRole('tab', { name: 'YAML 源码' }));
  await screen.findByText('models/metrics.yml');
  const lines = Array.from(container.querySelectorAll('.source-code li'));
  expect(lines.findIndex((line) => line.classList.contains('source-definition'))).toBe(5);
});

it('reports synchronous preview failure without polling', async () => {
  const client = api('TABLE');
  client.previewDbtDataset.mockRejectedValueOnce(new Error('network'));
  const view = render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  await screen.findByText('TABLE title');
  fireEvent.click(screen.getByRole('button', { name: '预览数据' }));
  expect(await screen.findByText('预览读取失败', {}, { timeout: 3000 })).toBeTruthy();
  const calls = 0;
  view.unmount();
  await new Promise((resolve) => setTimeout(resolve, 1100));
  expect(client.getDbtQuery).toHaveBeenCalledTimes(calls);
});

it('历史目录不预判物理查询可用性，执行失败由服务报告', async () => {
  const client = api('TABLE');
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  const preview = await screen.findByRole('button', { name: '预览数据' });
  expect(preview.hasAttribute('disabled')).toBe(false);
  fireEvent.click(preview);
  expect(client.previewDbtDataset).toHaveBeenCalled();
  expect(screen.getByRole('heading', { name: 'TABLE title' })).toBeTruthy();
});

it('PREVIEW同步返回数据且不读取查询状态', async () => {
  const client = api('TABLE');
  client.previewDbtDataset.mockResolvedValue({
    queryId: '55555555-5555-4555-8555-555555555555',
    buildId: 'release-1',
    status: 'SUCCEEDED',
    mode: 'PREVIEW',
    columns: [{ name: 'value', type: 'STRING' }],
    rows: [['sync-preview-value']],
  });
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  fireEvent.click(await screen.findByRole('button', { name: '预览数据' }));
  await screen.findByText('sync-preview-value');
  expect(client.getDbtQuery).not.toHaveBeenCalled();
});

it('PREVIEW受理即返回未知终态时不再请求状态', async () => {
  const client = api('TABLE');
  client.previewDbtDataset.mockResolvedValue({
    queryId: '55555555-5555-4555-8555-555555555555',
    buildId: 'release-1',
    status: 'OUTCOME_UNKNOWN',
    mode: 'PREVIEW',
    errorCode: 'PREVIEW_OUTCOME_UNKNOWN',
    errorMessage: 'receipt diagnostic',
  });
  render(
    <DbtResourceDetail
      projectId="demo"
      buildId="release-1"
      resourceId="r"
      onSelect={vi.fn()}
      api={client as never}
    />,
  );
  fireEvent.click(await screen.findByRole('button', { name: '预览数据' }));
  await screen.findByText(/receipt diagnostic/);
  expect(client.getDbtQuery).not.toHaveBeenCalled();
});

it('opens analysis from the shared overview of a granted metric', async () => {
  const explore = vi.fn();
  const resource = publishedTreeResource(
    nativeResource('metric.pkg.sales', { name: 'sales', resource_type: 'metric', granted: true }),
  );
  render(
    <MemoryRouter>
      <DbtResourceDetail
        projectId=""
        resourceId={resource.resourceId}
        catalogSummary={resource}
        buildId="b"
        onSelect={vi.fn()}
        onExplore={explore}
      />
    </MemoryRouter>,
  );
  expect(await screen.findByRole('heading', { name: '基本信息' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: /开始分析/ }));
  expect(explore).toHaveBeenCalledWith('sales');
  expect(screen.getByRole('tab', { name: /血缘/ }).getAttribute('aria-disabled')).toBe('true');
  expect(screen.queryByRole('button', { name: '查看源码' })).toBeNull();
});
it('marks inaccessible metrics, dimensions and datasets without offering query or preview', async () => {
  render(
    <MemoryRouter>
      {['metric', 'dimension', 'model'].map((kind) => (
        <DbtResourceDetail
          projectId=""
          resourceId={kind}
          onSelect={vi.fn()}
          onExplore={vi.fn()}
          key={kind}
          catalogSummary={publishedTreeResource(
            nativeResource(kind, { resource_type: kind, granted: false }),
          )}
          buildId="b"
        />
      ))}
    </MemoryRouter>,
  );
  expect(await screen.findAllByText('无权限')).toHaveLength(3);
  expect(screen.queryByRole('button', { name: /开始分析/ })).toBeNull();
  expect(screen.queryByRole('button', { name: '预览数据' })).toBeNull();
});
