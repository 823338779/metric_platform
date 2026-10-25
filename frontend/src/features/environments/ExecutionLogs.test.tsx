import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ExecutionLogs } from './ExecutionLogs';
afterEach(cleanup);

it('保持在底部可切换，开启时跟随新日志，关闭后保留阅读位置', async () => {
  const followLabel = '保持在底部';
  const refreshLabel = '刷新日志';
  const logRole = 'log';
  const buttonRole = 'button';
  const pressedAttribute = 'aria-pressed';
  const heightProperty = 'scrollHeight';
  const messages = ['第一条日志', '第二条日志', '第三条日志'];
  const logs = vi.fn();
  messages.forEach((message, index) =>
    logs.mockResolvedValueOnce({
      items: [{ logId: String(index), message }],
      hasMore: false,
    }),
  );
  const api = { logs, getBuild: vi.fn().mockResolvedValue({ buildStatus: 'SUCCEEDED' }) };
  render(<ExecutionLogs buildId="build" api={api as never} />);
  await screen.findByText(messages[0]);
  const terminal = screen.getByRole(logRole);
  const follow = screen.getByRole(buttonRole, { name: followLabel });
  Object.defineProperty(terminal, heightProperty, { configurable: true, value: 600 });
  terminal.scrollTop = 80;
  terminal.scrollLeft = 30;
  expect(follow).toHaveAttribute(pressedAttribute, String(false));
  fireEvent.click(follow);
  expect(follow).toHaveAttribute(pressedAttribute, String(true));
  expect(terminal.scrollTop).toBe(600);
  expect(terminal.scrollLeft).toBe(30);
  Object.defineProperty(terminal, heightProperty, { configurable: true, value: 900 });
  fireEvent.click(screen.getByRole(buttonRole, { name: refreshLabel }));
  await screen.findByText(messages[1]);
  expect(terminal.scrollTop).toBe(900);
  fireEvent.click(follow);
  terminal.scrollTop = 120;
  fireEvent.click(screen.getByRole(buttonRole, { name: refreshLabel }));
  await screen.findByText(messages[2]);
  expect(follow).toHaveAttribute(pressedAttribute, String(false));
  expect(terminal.scrollTop).toBe(120);
});

it('串行追加结构化日志，以字符串 ID 去重且保留空页游标', async () => {
  const logs = vi
    .fn()
    .mockResolvedValueOnce({
      items: [{ logId: '9007199254740993', message: 'first' }],
      nextCursor: 'a',
      hasMore: true,
    })
    .mockResolvedValueOnce({
      items: [
        { logId: '9007199254740993', message: 'first' },
        { logId: '9007199254740994', message: 'second' },
      ],
      nextCursor: 'b',
      hasMore: false,
    });
  const api = { logs, getBuild: vi.fn().mockResolvedValue({ buildStatus: 'SUCCEEDED' }) };
  render(<ExecutionLogs buildId="build" api={api as never} />);
  await screen.findByText(/second/);
  expect(screen.getAllByText(/first/)).toHaveLength(1);
  expect(logs.mock.calls[1][1]).toBe('a');
  expect(logs.mock.calls).toHaveLength(2);
});
it('切换构建取消请求，旧响应不能写入新列表', async () => {
  let finish!: (value: unknown) => void;
  const logs = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValue({
      items: [{ logId: '2', message: 'new' }],
      nextCursor: 'n',
      hasMore: false,
    });
  const api = { logs, getBuild: vi.fn().mockResolvedValue({ buildStatus: 'SUCCEEDED' }) };
  const view = render(<ExecutionLogs key="old" buildId="old" api={api as never} />);
  await waitFor(() => expect(logs).toHaveBeenCalledTimes(1));
  view.rerender(<ExecutionLogs key="new" buildId="new" api={api as never} />);
  await screen.findByText(/new/);
  await act(async () =>
    finish({ items: [{ logId: '1', message: 'obsolete' }], nextCursor: 'o', hasMore: false }),
  );
  expect(screen.queryByText(/obsolete/)).not.toBeInTheDocument();
  expect(logs.mock.calls[0][2].aborted).toBe(true);
});
it('空页游标持续续读，终态后再读取并保留手动刷新游标', async () => {
  const api = {
    getBuild: vi
      .fn()
      .mockResolvedValueOnce({ buildStatus: 'OUTCOME_UNKNOWN' })
      .mockResolvedValue({ buildStatus: 'SUCCEEDED' }),
    logs: vi
      .fn()
      .mockResolvedValueOnce({ items: [], nextCursor: 'empty-cursor', hasMore: false })
      .mockResolvedValueOnce({
        items: [{ logId: '1', message: 'arrived' }],
        nextCursor: 'tail',
        hasMore: false,
      })
      .mockResolvedValue({ items: [], nextCursor: 'tail', hasMore: false }),
  };
  render(<ExecutionLogs buildId="build" api={api as never} />);
  await screen.findByText('暂无日志');
  await screen.findByText('arrived', {}, { timeout: 2500 });
  expect(api.logs.mock.calls[1][1]).toBe('empty-cursor');
  fireEvent.click(screen.getByRole('button', { name: '刷新日志' }));
  await waitFor(() => expect(api.logs).toHaveBeenCalledTimes(3));
  expect(api.logs.mock.calls[2][1]).toBe('tail');
  expect(screen.getByText('arrived')).toBeInTheDocument();
});
it('日志页失败后手动重试沿用成功游标并保留已读内容', async () => {
  const api = {
    getBuild: vi.fn().mockResolvedValue({ buildStatus: 'SUCCEEDED' }),
    logs: vi
      .fn()
      .mockResolvedValueOnce({
        items: [{ logId: '1', message: 'kept' }],
        nextCursor: 'checkpoint',
        hasMore: true,
      })
      .mockRejectedValueOnce(new Error('storage unavailable'))
      .mockResolvedValue({ items: [], nextCursor: 'checkpoint', hasMore: false }),
  };
  render(<ExecutionLogs buildId="build" api={api as never} />);
  await screen.findByText('storage unavailable');
  fireEvent.click(screen.getByRole('button', { name: '刷新日志' }));
  await waitFor(() => expect(api.logs).toHaveBeenCalledTimes(3));
  expect(api.logs.mock.calls[2][1]).toBe('checkpoint');
  expect(screen.getByText('kept')).toBeInTheDocument();
});
