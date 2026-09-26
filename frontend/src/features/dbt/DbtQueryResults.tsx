import { Alert, Card, Radio, Table, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { DbtQueryStatus } from '../../api/types';

echarts.use([
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);

function chartData(result: DbtQueryStatus) {
  if (!result.columns || !result.rows || result.columns.length < 2 || result.rows.length === 0)
    return null;
  const x = result.columns[0];
  if (!/CHAR|TEXT|DATE|TIME/i.test(x.type)) return null;
  if (
    result.columns
      .slice(1)
      .some((column) => !/DECIMAL|NUMERIC|INT|FLOAT|DOUBLE|REAL/i.test(column.type))
  )
    return null;
  const numeric = result.columns
    .slice(1)
    .map((column, index) => ({ column, index: index + 1 }))
    .filter(({ column }) => /DECIMAL|NUMERIC|INT|FLOAT|DOUBLE|REAL/i.test(column.type));
  if (!numeric.length) return null;
  for (const row of result.rows) {
    for (const { index } of numeric) {
      if (
        row[index] === null ||
        !Number.isFinite(Number(row[index])) ||
        Math.abs(Number(row[index])) > Number.MAX_SAFE_INTEGER
      )
        return null;
    }
  }
  return {
    categories: result.rows.map((row) => String(row[0] ?? '')),
    series: numeric.map(({ column, index }) => ({
      name: column.name,
      data: result.rows!.map((row) => Number(row[index])),
    })),
    time: /DATE|TIME/i.test(x.type),
  };
}

/** 表格始终使用服务返回的原值，图形只使用可安全解释的副本。 */
export function DbtQueryResults({ result }: { result: DbtQueryStatus }) {
  const [chartType, setChartType] = useState<'line' | 'bar'>('bar');
  const container = useRef<HTMLDivElement>(null);
  const data = chartData(result);
  useEffect(() => {
    if (!data || !container.current) return;
    const chart = echarts.init(container.current);
    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: {},
      grid: { containLabel: true },
      xAxis: { type: 'category', data: data.categories },
      yAxis: { type: 'value' },
      series: data.series.map((series) => ({ ...series, type: chartType })),
    });
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      chart.dispose();
    };
  }, [result, chartType]);
  return (
    <Card title="查询结果">
      <Typography.Text>状态：{result.status}</Typography.Text>
      {result.errorCode && <Alert type="error" title={result.errorCode} />}
      {result.truncated && <Alert type="warning" title="结果已截断，请缩小范围或调整行数" />}
      {result.columns && result.rows && (
        <Table
          pagination={{ pageSize: 20 }}
          scroll={{ x: true }}
          columns={result.columns.map((column, index) => ({
            title: `${column.name} (${column.type})`,
            dataIndex: String(index),
          }))}
          dataSource={result.rows.map((row, index) => ({
            key: index,
            ...Object.fromEntries(
              row.map((cell, column) => [String(column), cell == null ? '' : String(cell)]),
            ),
          }))}
        />
      )}
      {data && (
        <>
          <Radio.Group
            value={chartType}
            onChange={(event) => setChartType(event.target.value)}
            options={[
              { label: '柱状图', value: 'bar' },
              { label: '折线图', value: 'line' },
            ]}
          />
          <div
            ref={container}
            role="img"
            aria-label={data.time ? '时间序列图' : '指标图'}
            style={{ width: '100%', height: 320 }}
          />
        </>
      )}
    </Card>
  );
}
