import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { PlatformLayout } from './PlatformLayout';
import { describe, it, expect } from 'vitest';
describe('PlatformLayout', () => {
  it('shows resource centers and workbench', () => {
    render(
      <MemoryRouter>
        <PlatformLayout />
      </MemoryRouter>,
    );
    expect(screen.getByText('数据集')).toBeTruthy();
    expect(screen.getByText('指标')).toBeTruthy();
    expect(screen.getByText('维度')).toBeTruthy();
    expect(screen.getByText('查询工作台')).toBeTruthy();
  });
});
