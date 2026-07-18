import type { QueryResponse } from '../../api/types';

export function queryResultCsv(result: Pick<QueryResponse, 'columns' | 'rows'>) {
  const header = result.columns.map((column) => csvCell(column.name)).join(',');
  const rows = result.rows.map((row) => row.map(csvCell).join(','));
  return `\uFEFF${[header, ...rows].join('\r\n')}`;
}

export function exportQueryResultCsv(result: QueryResponse) {
  const url = URL.createObjectURL(
    new Blob([queryResultCsv(result)], { type: 'text/csv;charset=utf-8' }),
  );
  const link = document.createElement('a');
  link.href = url;
  link.download = `metric-query-${result.queryId}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

function csvCell(value: unknown) {
  if (value === null || value === undefined) return '';
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}
