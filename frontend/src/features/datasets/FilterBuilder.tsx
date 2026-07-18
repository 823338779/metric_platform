import { Button, Input, Select, Space } from 'antd';
import type { DatasetField, FilterExpression, FilterOperator } from '../../api/types';
const operators: FilterOperator[] = [
  'EQ',
  'NE',
  'GT',
  'GTE',
  'LT',
  'LTE',
  'BETWEEN',
  'IN',
  'CONTAINS',
  'IS_NULL',
  'IS_NOT_NULL',
];
export function FilterBuilder({
  value,
  onChange,
  fields,
}: {
  value: FilterExpression;
  onChange: (v: FilterExpression) => void;
  fields: DatasetField[];
}) {
  if (value.logicalOperator)
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Space>
          <Select
            value={value.logicalOperator}
            options={['AND', 'OR'].map((v) => ({ value: v }))}
            onChange={(logicalOperator) => onChange({ ...value, logicalOperator })}
          />
          <Button
            onClick={() =>
              onChange({
                ...value,
                children: [
                  ...(value.children ?? []),
                  { fieldId: fields[0]?.id, operator: 'EQ', values: [''] },
                ],
              })
            }
          >
            添加条件
          </Button>
        </Space>
        {(value.children ?? []).map((child, i) => (
          <FilterBuilder
            key={i}
            value={child}
            fields={fields}
            onChange={(next) =>
              onChange({ ...value, children: value.children?.map((c, j) => (j === i ? next : c)) })
            }
          />
        ))}
      </Space>
    );
  const count =
    value.operator === 'BETWEEN'
      ? 2
      : value.operator === 'IS_NULL' || value.operator === 'IS_NOT_NULL'
        ? 0
        : 1;
  return (
    <Space wrap>
      <Select
        style={{ width: 160 }}
        value={value.fieldId}
        options={fields.map((f) => ({ value: f.id, label: f.displayName }))}
        onChange={(fieldId) => onChange({ ...value, fieldId })}
      />
      <Select
        style={{ width: 150 }}
        value={value.operator}
        options={operators.map((v) => ({ value: v }))}
        onChange={(operator) =>
          onChange({ ...value, operator, values: operator === 'BETWEEN' ? ['', ''] : [] })
        }
      />
      {Array.from({ length: count }, (_, i) => (
        <Input
          key={i}
          aria-label={`筛选值 ${i + 1}`}
          value={String(value.values?.[i] ?? '')}
          onChange={(e) => {
            const values = [...(value.values ?? [])];
            values[i] = e.target.value;
            onChange({ ...value, values });
          }}
        />
      ))}
    </Space>
  );
}
