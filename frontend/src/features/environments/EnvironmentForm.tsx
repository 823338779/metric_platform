import { Button } from 'antd';
import { useState } from 'react';
import type { EnvironmentInput } from '../../api/environmentApi';

const STARROCKS = 'starrocks';
const POSTGRES = 'postgres';
const DUCKDB = 'duckdb';
const COPY = {
  basic: '基本信息',
  connection: '数据库连接',
  execution: '执行设置',
  credentials: '填写服务端配置的凭据名称，无需在这里输入密码。',
};
const DEFAULT: EnvironmentInput = {
  name: '',
  connection: { type: STARROCKS, instanceId: '', host: '', port: 9030 },
  credentialRef: '',
  catalog: 'default_catalog',
  schema: '',
  threads: 2,
  businessTimezone: 'Asia/Shanghai',
};

export function EnvironmentForm({
  initial = DEFAULT,
  busy,
  onSave,
}: {
  initial?: EnvironmentInput;
  busy: boolean;
  onSave: (input: EnvironmentInput) => void;
}) {
  const [value, setValue] = useState(initial);
  const text = (label: string, field: keyof Omit<EnvironmentInput, 'connection' | 'threads'>) => (
    <label>
      {label}
      <input
        required
        aria-label={label}
        value={value[field]}
        onChange={(event) => setValue({ ...value, [field]: event.target.value })}
      />
    </label>
  );
  return (
    <form
      className="environment-form"
      onSubmit={(event) => {
        event.preventDefault();
        onSave(value);
      }}
    >
      <fieldset>
        <legend>{COPY.basic}</legend>
        {text('环境名称', 'name')}
      </fieldset>
      <fieldset>
        <legend>{COPY.connection}</legend>
        <label>
          适配器
          <select
            aria-label="适配器"
            value={value.connection.type}
            onChange={(event) => {
              const type = event.target.value as typeof value.connection.type;
              setValue({
                ...value,
                connection: {
                  instanceId: value.connection.instanceId,
                  type,
                  ...(type === DUCKDB
                    ? { path: '' }
                    : { host: '', port: type === POSTGRES ? 5432 : 9030 }),
                },
              });
            }}
          >
            <option value={STARROCKS}>StarRocks</option>
            <option value={POSTGRES}>PostgreSQL</option>
            <option value={DUCKDB}>DuckDB</option>
          </select>
        </label>
        <label>
          物理实例 ID
          <input
            required
            value={value.connection.instanceId}
            onChange={(event) =>
              setValue({
                ...value,
                connection: { ...value.connection, instanceId: event.target.value },
              })
            }
          />
        </label>
        {value.connection.type === DUCKDB ? (
          <label>
            数据库文件
            <input
              required
              value={value.connection.path ?? ''}
              onChange={(event) =>
                setValue({
                  ...value,
                  connection: {
                    type: DUCKDB,
                    instanceId: value.connection.instanceId,
                    path: event.target.value,
                  },
                })
              }
            />
          </label>
        ) : (
          <>
            <label>
              数据库主机
              <input
                required
                value={value.connection.host ?? ''}
                onChange={(event) =>
                  setValue({
                    ...value,
                    connection: { ...value.connection, host: event.target.value, path: undefined },
                  })
                }
              />
            </label>
            <label>
              端口
              <input
                type="number"
                required
                min={1}
                max={65535}
                value={value.connection.port ?? 9030}
                onChange={(event) =>
                  setValue({
                    ...value,
                    connection: { ...value.connection, port: Number(event.target.value) },
                  })
                }
              />
            </label>
          </>
        )}
        {text('凭据引用', 'credentialRef')}
        <p className="form-help">{COPY.credentials}</p>
      </fieldset>
      <fieldset>
        <legend>{COPY.execution}</legend>
        {text('Catalog', 'catalog')}
        {text('Schema', 'schema')}
        <label>
          构建并行度
          <input
            type="number"
            min={1}
            max={32}
            required
            value={value.threads}
            onChange={(event) => setValue({ ...value, threads: Number(event.target.value) })}
          />
        </label>
        {text('业务时区', 'businessTimezone')}
      </fieldset>
      <footer className="form-actions">
        <Button htmlType="submit" type="primary" loading={busy}>
          保存环境
        </Button>
      </footer>
    </form>
  );
}
