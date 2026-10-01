/** 当前平台统一使用北京时间；MetricFlow 支持的 2000–2040 年范围内固定为 UTC+08:00。 */
const BUSINESS_OFFSET = '+08:00';
const BUSINESS_OFFSET_MS = 8 * 60 * 60 * 1000;
const UTC_SUFFIX = 'Z';
const MIDNIGHT = 'T00:00';

/** 用 UTC 日历运算表达北京时间，避免浏览器时区及夏令时改变日期。 */
export function businessDateTime(now: Date) {
  return new Date(now.getTime() + BUSINESS_OFFSET_MS).toISOString().slice(0, 16);
}

export function businessTimeToIso(value: string) {
  return new Date(`${value}${BUSINESS_OFFSET}`).toISOString();
}

export function businessTimeRange(now: Date, days?: number): [string, string] {
  const end = businessDateTime(now);
  const start = new Date(`${end.slice(0, 10)}${MIDNIGHT}${UTC_SUFFIX}`);
  if (days) start.setUTCDate(start.getUTCDate() - days + 1);
  else start.setUTCDate(1);
  return [start.toISOString().slice(0, 16), end];
}
