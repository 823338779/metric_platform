import { useId, useRef, useState } from 'react';
import { Dropdown, Popover } from 'antd';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CalendarOutlined,
  CheckOutlined,
  CloseOutlined,
  DownOutlined,
  BarsOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { GRAIN_LABELS, type AnalysisChoice } from './AnalysisControls';
import './query-selection.css';

const COPY = {
  add: '添加',
  search: '搜索',
  choose: '选择',
  remove: '移除',
  grain: '时间粒度',
  unknown: '类型待确认',
  empty: '没有匹配的选项',
  raw: '原始粒度',
  direction: '排序方向：',
  ascending: '升序',
  descending: '降序',
  timeUnknown: '时间 · 类型待确认',
};
export const ASCENDING = 'ASC';
export const DESCENDING = 'DESC';
export type QueryDirection = typeof ASCENDING | typeof DESCENDING;
const EMPTY = '';
const CLICK = 'click';
const BOTTOM = 'bottom';

export type QueryChoice = AnalysisChoice & {
  /** 当前固定版本声明或直接列映射确定的数据类型。 */
  dataType?: string;
  /** 服务允许的时间粒度；不存在时不展示粒度菜单。 */
  granularities?: string[];
};

export function QuerySelection({
  label,
  options,
  selected,
  onChange,
  grains = {},
  onGrainChange,
  disabled = false,
  emptyText = COPY.empty,
  single = false,
  showTypes = true,
  suggestWhenEmpty = true,
  directions = {},
  onDirectionChange,
}: {
  label: string;
  options: QueryChoice[];
  selected: string[];
  onChange: (values: string[]) => void;
  grains?: Record<string, string>;
  onGrainChange?: (id: string, grain: string) => void;
  disabled?: boolean;
  emptyText?: string;
  single?: boolean;
  showTypes?: boolean;
  /** 首次分组直接展示候选，已有选择后通过加号继续添加。 */
  suggestWhenEmpty?: boolean;
  /** 每个排序字段的方向，字段顺序由 selected 决定。 */
  directions?: Record<string, QueryDirection>;
  onDirectionChange?: (id: string, direction: QueryDirection) => void;
}) {
  const [open, setOpen] = useState(suggestWhenEmpty && !selected.length);
  const [search, setSearch] = useState(EMPTY);
  const descriptionId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const initialPicker = suggestWhenEmpty && !selected.length;
  const inline = initialPicker && open && !disabled && options.length > 0;
  const visible = options.filter((option) =>
    `${option.label} ${option.dataType ?? EMPTY}`
      .toLocaleLowerCase()
      .includes(search.trim().toLocaleLowerCase()),
  );
  const change = (value: string) => {
    onChange(
      selected.includes(value)
        ? selected.filter((item) => item !== value)
        : single
          ? [value]
          : [...selected, value],
    );
    setOpen(false);
    setSearch(EMPTY);
    trigger.current?.focus();
  };
  const picker = (
    <section
      className="query-picker"
      aria-label={`${COPY.choose}${label}`}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          setOpen(false);
          trigger.current?.focus();
        }
      }}
    >
      <label className="query-picker-search">
        <SearchOutlined />
        <input
          aria-label={`${COPY.search}${label}`}
          placeholder={`${COPY.search}${label}`}
          autoFocus={!inline}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </label>
      <div className="query-picker-list">
        {visible.map((option) => (
          <button
            key={option.value}
            type="button"
            aria-label={option.label}
            aria-describedby={
              showTypes ? `${descriptionId}-${encodeURIComponent(option.value)}` : undefined
            }
            aria-pressed={selected.includes(option.value)}
            disabled={disabled}
            onClick={() => change(option.value)}
          >
            {showTypes && (option.granularities?.length ? <CalendarOutlined /> : <BarsOutlined />)}
            <span className="query-choice-name">{option.label}</span>
            {showTypes && (
              <span
                className="query-data-type"
                id={`${descriptionId}-${encodeURIComponent(option.value)}`}
              >
                {option.dataType ??
                  (option.granularities?.length ? COPY.timeUnknown : COPY.unknown)}
              </span>
            )}
            {selected.includes(option.value) && <CheckOutlined />}
          </button>
        ))}
        {!visible.length && <p role="status">{search ? COPY.empty : emptyText}</p>}
      </div>
    </section>
  );
  return (
    <div className="query-selection" role="group" aria-label={label}>
      <div className="query-tags">
        {selected.map((id) => {
          const option = options.find((item) => item.value === id);
          if (!option) return null;
          return (
            <span className="query-tag" key={id}>
              {option.granularities?.length && onGrainChange ? (
                <Dropdown
                  trigger={[CLICK]}
                  autoFocus
                  disabled={disabled}
                  menu={{
                    selectedKeys: [grains[id] ?? EMPTY],
                    items: [
                      { key: EMPTY, label: COPY.raw },
                      ...option.granularities.map((grain) => ({
                        key: grain,
                        label: GRAIN_LABELS[grain] ?? grain,
                      })),
                    ],
                    onClick: ({ key }) => onGrainChange(id, key),
                  }}
                >
                  <button
                    type="button"
                    disabled={disabled}
                    aria-label={`${option.label}${COPY.grain}`}
                    aria-describedby={`${descriptionId}-grain-${encodeURIComponent(id)}`}
                  >
                    <CalendarOutlined />
                    <span id={`${descriptionId}-grain-${encodeURIComponent(id)}`}>
                      {option.label}：{GRAIN_LABELS[grains[id]] ?? (grains[id] || COPY.raw)}
                    </span>
                    <DownOutlined />
                  </button>
                </Dropdown>
              ) : onDirectionChange ? (
                <button
                  type="button"
                  disabled={disabled}
                  aria-label={`${option.label}${COPY.direction}${directions[id] === DESCENDING ? COPY.descending : COPY.ascending}`}
                  onClick={() =>
                    onDirectionChange(id, directions[id] === DESCENDING ? ASCENDING : DESCENDING)
                  }
                >
                  <span>{option.label}</span>
                  {directions[id] === DESCENDING ? <ArrowDownOutlined /> : <ArrowUpOutlined />}
                </button>
              ) : (
                <span className="query-tag-name">{option.label}</span>
              )}
              <button
                className="query-tag-remove"
                type="button"
                disabled={disabled}
                aria-label={`${COPY.remove}${label} ${option.label}`}
                onClick={() => onChange(selected.filter((value) => value !== id))}
              >
                <CloseOutlined />
              </button>
            </span>
          );
        })}
        <Popover
          trigger={CLICK}
          destroyOnHidden
          placement={BOTTOM}
          open={!initialPicker && open && !disabled}
          onOpenChange={(value) => {
            if (initialPicker) return;
            setOpen(value);
            if (value) setSearch(EMPTY);
          }}
          content={picker}
        >
          <button
            ref={trigger}
            className="query-add"
            type="button"
            disabled={disabled || !options.length}
            aria-label={`${COPY.add}${label}`}
            aria-expanded={!disabled && options.length > 0 && open}
            onClick={() => {
              if (initialPicker) {
                setOpen(!open);
                setSearch(EMPTY);
              }
            }}
          >
            <PlusOutlined />
            {!selected.length && `${COPY.add}${label}`}
          </button>
        </Popover>
        {(disabled || !options.length) && !selected.length && (
          <span className="analysis-inline-empty">{emptyText}</span>
        )}
      </div>
      {inline && picker}
    </div>
  );
}
