import { Alert, Card, Empty, Radio, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { DbtQueryStatus } from '../../api/types';
import { buildVisualization, visualizationFields } from './queryVisualization';
import './exploration.css';
import { ChoiceChips } from './AnalysisControls';

const TIME = /DATE|TIME/i;
const LINE = 'line';
const BAR = 'bar';
const DETAILS = 'details';
const VISUAL = 'visual';
const COMPLETED = 'COMPLETED';
const QUERY = 'QUERY';
const EXPLAIN = 'EXPLAIN';
const FAILED = 'FAILED';
const CANCELLED = 'CANCELLED';
const SQL_TITLE = '查询 SQL';
const SQL_LABEL = '生成的 SQL';
const SQL_PROGRESS = '正在生成 SQL，请稍候…';
const SQL_DESCRIPTION = '此 SQL 根据当前查询条件生成，未执行取数。';
const SQL_CANCELLED = 'SQL 生成已取消';
echarts.use([
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);

/** 按已提交查询识别指标，表格始终保留服务原值。 */
export function DbtQueryResults({
  result,
  metricNames,
}: {
  result: DbtQueryStatus;
  metricNames?: string[];
}) {
  const fields = useMemo(() => visualizationFields(result, metricNames), [result, metricNames]);
  const initialX =
    fields.dimensions.find((column) => TIME.test(column.type)) ?? fields.dimensions[0];
  const [measure, setMeasure] = useState(fields.measures[0]?.index ?? -1);
  const [x, setX] = useState(initialX?.index ?? -1);
  const [seriesDimensions, setSeriesDimensions] = useState(
    fields.dimensions
      .filter((column) => column.index !== initialX?.index)
      .map((column) => column.index),
  );
  const [chartType, setChartType] = useState<'line' | 'bar'>(
    initialX && TIME.test(initialX.type) ? LINE : BAR,
  );
  const [view, setView] = useState(VISUAL);
  const container = useRef<HTMLDivElement>(null);
  const data = useMemo(
    () => buildVisualization(result, measure, x, seriesDimensions, metricNames),
    [result, measure, x, seriesDimensions, metricNames],
  );
  const complete = result.status === COMPLETED;
  const ungrouped =
    metricNames &&
    fields.dimensions.length === 0 &&
    result.rows?.length === 1 &&
    result.mode === QUERY;

  // 异步任务从 PENDING 进入完成态后，按这次结果初始化图形字段。
  useEffect(() => {
    setMeasure(fields.measures[0]?.index ?? -1);
    setX(initialX?.index ?? -1);
    setSeriesDimensions(
      fields.dimensions
        .filter((column) => column.index !== initialX?.index)
        .map((column) => column.index),
    );
    setChartType(initialX && TIME.test(initialX.type) ? LINE : BAR);
  }, [result.queryId, result.columns]);

  useEffect(() => {
    if (!complete || view === DETAILS || !data.series || !container.current) return;
    const chart = echarts.init(container.current);
    chart.setOption({
      tooltip: { trigger: 'axis', confine: true },
      legend: { type: 'scroll', bottom: 0 },
      grid: { containLabel: true, left: 16, right: 24, top: 24, bottom: 64 },
      xAxis: { type: 'category', data: data.categories },
      yAxis: { type: 'value' },
      series: data.series.map((series) => ({ ...series, type: chartType, connectNulls: false })),
    });
    const resize = () => chart.resize();
    const observer = new ResizeObserver(resize);
    observer.observe(container.current);
    window.addEventListener('resize', resize);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', resize);
      chart.dispose();
    };
  }, [data, chartType, view, complete]);

  // 解释任务没有数据行，单独展示 SQL 和任务状态，避免误报“没有数据”。
  if (result.mode === EXPLAIN) {
    const failed = result.status === FAILED;
    const cancelled = result.status === CANCELLED;
    return (
      <Card
        title={SQL_TITLE}
        extra={
          <Tag color={complete ? 'success' : failed ? 'error' : 'processing'}>
            状态：{result.status}
          </Tag>
        }
      >
        {complete ? (
          result.sql?.trim() ? (
            <>
              <Typography.Paragraph type="secondary">{SQL_DESCRIPTION}</Typography.Paragraph>
              <Typography.Paragraph copyable={{ text: result.sql }}>
                <pre aria-label={SQL_LABEL} style={{ overflow: 'auto', maxHeight: 600 }}>
                  {result.sql}
                </pre>
              </Typography.Paragraph>
            </>
          ) : (
            <Alert type="warning" title="运行服务未返回 SQL，请重新解释查询" />
          )
        ) : failed ? (
          <Alert
            type="error"
            title="SQL 生成失败"
            description={result.errorCode ?? '请检查查询条件后重试'}
          />
        ) : cancelled ? (
          <Alert type="warning" title={SQL_CANCELLED} />
        ) : (
          <Alert type="info" title={SQL_PROGRESS} />
        )}
      </Card>
    );
  }

  return (
    <Card
      title="图表与明细"
      extra={<Tag color={complete ? 'success' : 'processing'}>状态：{result.status}</Tag>}
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {result.errorCode && <Alert type="error" title={result.errorCode} />}
        {result.truncated && <Alert type="warning" title="结果已截断，请缩小范围或调整行数" />}
        {complete && result.rows?.length === 0 && (
          <Empty description="当前条件下没有数据，试试调整日期或筛选条件" />
        )}
        {complete && !!result.rows?.length && (
          <Radio.Group
            value={view}
            onChange={(event) => setView(event.target.value)}
            options={[
              { label: '可视化与明细', value: VISUAL },
              { label: '仅明细', value: DETAILS },
            ]}
          />
        )}
        {complete && view !== DETAILS && ungrouped && (
          <div role="region" aria-label="指标数值" className="metric-value-grid">
            {fields.measures.map((column) => (
              <Card key={column.name} size="small">
                <Typography.Text type="secondary">{column.name}</Typography.Text>
                <Typography.Title level={2}>
                  {column.name}：{String(result.rows![0][column.index] ?? '—')}
                </Typography.Title>
              </Card>
            ))}
          </div>
        )}
        {complete &&
          view !== DETAILS &&
          fields.dimensions.length > 0 &&
          fields.measures.length > 0 && (
            <>
              <div className="chart-controls">
                <div>
                  <p>展示指标</p>
                  <ChoiceChips
                    label="展示指标"
                    single
                    selected={[String(measure)]}
                    options={fields.measures.map((column) => ({
                      value: String(column.index),
                      label: column.name,
                    }))}
                    onChange={(values) => {
                      if (values[0]) setMeasure(Number(values[0]));
                    }}
                  />
                </div>
                <div>
                  <p>横轴</p>
                  <ChoiceChips
                    label="横轴"
                    single
                    selected={[String(x)]}
                    options={fields.dimensions.map((column) => ({
                      value: String(column.index),
                      label: column.name,
                    }))}
                    onChange={(values) => {
                      if (!values[0]) return;
                      const index = Number(values[0]);
                      setX(index);
                      setSeriesDimensions(
                        fields.dimensions
                          .filter((column) => column.index !== index)
                          .map((column) => column.index),
                      );
                      setChartType(TIME.test(result.columns![index].type) ? LINE : BAR);
                    }}
                  />
                </div>
                {metricNames && fields.dimensions.length > 1 && (
                  <div>
                    <p>系列维度</p>
                    <ChoiceChips
                      label="系列维度"
                      selected={seriesDimensions.map(String)}
                      options={fields.dimensions
                        .filter((column) => column.index !== x)
                        .map((column) => ({ value: String(column.index), label: column.name }))}
                      onChange={(values) => setSeriesDimensions(values.map(Number))}
                    />
                  </div>
                )}
              </div>
              {data.message && <Alert type="info" title={data.message} />}
              {data.series && (
                <>
                  <Radio.Group
                    value={chartType}
                    onChange={(event) => setChartType(event.target.value)}
                    options={[
                      { label: '柱状图', value: BAR },
                      { label: '折线图', value: LINE },
                    ]}
                  />
                  <div
                    ref={container}
                    role="img"
                    aria-label={data.time ? '时间序列图' : '指标图'}
                    style={{ width: '100%', height: 360 }}
                  />
                </>
              )}
            </>
          )}
        {result.columns && result.rows && (
          <Table
            size="small"
            pagination={{ pageSize: 20 }}
            scroll={{ x: 'max-content' }}
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
      </Space>
    </Card>
  );
}
