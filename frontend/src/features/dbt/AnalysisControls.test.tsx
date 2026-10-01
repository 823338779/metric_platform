import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { TimeRange } from './AnalysisControls';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

it('uses Beijing calendar boundaries for a seven-day range across months', () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-03T06:25:00Z'));
  const onChange = vi.fn();
  render(<TimeRange start="" end="" onChange={onChange} />);
  fireEvent.click(screen.getByRole('button', { name: '近 7 天' }));
  expect(onChange).toHaveBeenCalledWith('2026-09-27T00:00', '2026-10-03T14:25');
  fireEvent.click(screen.getByRole('button', { name: '本月' }));
  expect(onChange).toHaveBeenLastCalledWith('2026-10-01T00:00', '2026-10-03T14:25');
});

it('uses the Beijing date when the browser clock is still on the previous UTC day', () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-09-30T17:25:00Z'));
  const onChange = vi.fn();
  render(<TimeRange start="" end="" onChange={onChange} />);
  fireEvent.click(screen.getByRole('button', { name: '本月' }));
  expect(onChange).toHaveBeenCalledWith('2026-10-01T00:00', '2026-10-01T01:25');
  fireEvent.click(screen.getByRole('button', { name: '近 7 天' }));
  expect(onChange).toHaveBeenLastCalledWith('2026-09-25T00:00', '2026-10-01T01:25');
});

it('clears both date bounds without submitting a query', () => {
  const onChange = vi.fn();
  render(<TimeRange start="2026-09-01T00:00" end="2026-09-30T23:59" onChange={onChange} />);
  fireEvent.click(screen.getByRole('button', { name: '不限时间' }));
  expect(onChange).toHaveBeenCalledTimes(1);
  expect(onChange).toHaveBeenCalledWith('', '');
});
