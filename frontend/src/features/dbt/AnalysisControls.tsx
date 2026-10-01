import {
  BarChartOutlined,
  CheckOutlined,
  CloseOutlined,
  FunctionOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { businessTimeRange } from './businessTime';
import './analysis-workspace.css';

/** 可见名称与请求身份分离，列表始终回传 API 提供的身份。 */
export type AnalysisChoice = { value: string; label: string; description?: string | null };
const COPY = {
  library: '指标库',
  search: '搜索指标',
  searchHint: '搜索名称或描述',
  empty: '没有匹配的指标',
  loading: '正在读取指标…',
  choose: '点击指标加入分析，可同时选择多个',
  selected: '已选指标',
  none: '从左侧指标库选择你想了解的数据',
  dimensions: '没有可用选项',
  clear: '不限时间',
  week: '近 7 天',
  month: '近 30 天',
  current: '本月',
  custom: '自定义时间',
  start: '开始时间',
  end: '结束时间',
  timezone: '北京时间 · Asia/Shanghai',
  timeHint: '查询范围按时间粒度对齐；按天查询包含起止日期的整天。',
  title: '让数据回答你的问题',
  hint: '选择指标，按需添加分组维度，然后运行查询。结果会显示在这里。',
};
const EMPTY = '';
const DATE_TYPE = 'datetime-local';
/** 展示使用中文，查询仍提交运行服务声明的原始粒度值。 */
export const GRAIN_LABELS: Record<string, string> = {
  day: '日',
  week: '周',
  month: '月',
  quarter: '季度',
  year: '年',
  hour: '小时',
  minute: '分钟',
};

export function MetricLibrary({
  options,
  selected,
  onChange,
  loading = false,
}: {
  options: AnalysisChoice[];
  selected: string[];
  onChange: (value: string[]) => void;
  loading?: boolean;
}) {
  const [search, setSearch] = useState(EMPTY);
  const visible = options.filter((option) =>
    `${option.label} ${option.description ?? EMPTY}`
      .toLocaleLowerCase()
      .includes(search.toLocaleLowerCase().trim()),
  );
  return (
    <aside className="analysis-library" aria-label={COPY.library}>
      <header>
        <h2>{COPY.library}</h2>
        <span>{options.length}</span>
      </header>
      <label className="analysis-search">
        <SearchOutlined />
        <input
          aria-label={COPY.search}
          placeholder={COPY.searchHint}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </label>
      <p className="analysis-library-hint">{COPY.choose}</p>
      <div className="analysis-metric-list" aria-label="指标">
        {visible.map((option) => {
          const checked = selected.includes(option.value);
          return (
            <button
              type="button"
              role="checkbox"
              aria-checked={checked}
              aria-label={option.label}
              key={option.value}
              className={`analysis-metric-item${checked ? ' is-selected' : EMPTY}`}
              onClick={() =>
                onChange(
                  checked
                    ? selected.filter((value) => value !== option.value)
                    : [...selected, option.value],
                )
              }
            >
              <FunctionOutlined />
              <span>
                <strong>{option.label}</strong>
                {option.description && <small>{option.description}</small>}
              </span>
              <span className="analysis-check">{checked && <CheckOutlined />}</span>
            </button>
          );
        })}
        {!visible.length && (
          <p className="analysis-inline-empty" role="status">
            {loading ? COPY.loading : COPY.empty}
          </p>
        )}
      </div>
      <footer>
        {COPY.selected} <strong>{selected.length}</strong>
      </footer>
    </aside>
  );
}

export function SelectedMetrics({
  options,
  selected,
  onChange,
}: {
  options: AnalysisChoice[];
  selected: string[];
  onChange: (value: string[]) => void;
}) {
  return (
    <div className="analysis-selected" aria-label={COPY.selected}>
      {selected.map((value) => (
        <button
          type="button"
          key={value}
          onClick={() => onChange(selected.filter((item) => item !== value))}
          aria-label={`移除指标 ${options.find((item) => item.value === value)?.label ?? value}`}
        >
          <FunctionOutlined />
          <span>{options.find((item) => item.value === value)?.label ?? value}</span>
          <CloseOutlined />
        </button>
      ))}
      {!selected.length && <p>{COPY.none}</p>}
    </div>
  );
}

/** 候选项直接呈现；大列表提供就地搜索，单选再次点击可清除。 */
export function ChoiceChips({
  label,
  options,
  selected,
  onChange,
  single = false,
  disabled = false,
  emptyText = COPY.dimensions,
}: {
  label: string;
  options: AnalysisChoice[];
  selected: string[];
  onChange: (value: string[]) => void;
  single?: boolean;
  disabled?: boolean;
  emptyText?: string;
}) {
  const [search, setSearch] = useState(EMPTY);
  const visible = options.filter((option) =>
    option.label.toLocaleLowerCase().includes(search.toLocaleLowerCase().trim()),
  );
  return (
    <div className="analysis-choices" role="group" aria-label={label}>
      {options.length > 10 && (
        <input
          className="analysis-choice-search"
          aria-label={`搜索${label}`}
          placeholder={`搜索${label}`}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      )}
      <div className="analysis-chip-list">
        {visible.map((option) => (
          <button
            type="button"
            key={option.value}
            disabled={disabled}
            aria-pressed={selected.includes(option.value)}
            onClick={() =>
              onChange(
                selected.includes(option.value)
                  ? selected.filter((value) => value !== option.value)
                  : single
                    ? [option.value]
                    : [...selected, option.value],
              )
            }
          >
            {selected.includes(option.value) && <CheckOutlined />} {option.label}
          </button>
        ))}
        {!visible.length && (
          <span className="analysis-inline-empty">{search ? COPY.empty : emptyText}</span>
        )}
      </div>
    </div>
  );
}

/** 输入和快捷范围统一采用业务时区，不依赖浏览器所在地区。 */
export function TimeRange({
  start,
  end,
  onChange,
}: {
  start: string;
  end: string;
  onChange: (start: string, end: string) => void;
}) {
  const [preset, setPreset] = useState(EMPTY);
  const choose = (label: string, days?: number) => {
    setPreset(label);
    if (label === COPY.clear) {
      onChange(EMPTY, EMPTY);
      return;
    }
    onChange(...businessTimeRange(new Date(), days));
  };
  return (
    <div className="analysis-time">
      <span className="analysis-library-hint">{COPY.timezone}</span>
      <div className="analysis-chip-list" role="group" aria-label="时间范围">
        {[COPY.clear, COPY.week, COPY.month, COPY.current].map((label, index) => (
          <button
            type="button"
            key={label}
            aria-pressed={!start && !end ? index === 0 : preset === label}
            onClick={() => choose(label, index === 1 ? 7 : index === 2 ? 30 : undefined)}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="analysis-date-inputs" aria-label={COPY.custom}>
        <label>
          {COPY.start}
          <input
            aria-label={COPY.start}
            type={DATE_TYPE}
            value={start}
            onChange={(event) => {
              setPreset(EMPTY);
              onChange(event.target.value, end);
            }}
          />
        </label>
        <label>
          {COPY.end}
          <input
            aria-label={COPY.end}
            type={DATE_TYPE}
            value={end}
            onChange={(event) => {
              setPreset(EMPTY);
              onChange(start, event.target.value);
            }}
          />
        </label>
      </div>
      <span className="analysis-library-hint">{COPY.timeHint}</span>
    </div>
  );
}

export function AnalysisEmpty() {
  return (
    <div className="analysis-empty" role="status">
      <BarChartOutlined />
      <h3>{COPY.title}</h3>
      <p>{COPY.hint}</p>
    </div>
  );
}
