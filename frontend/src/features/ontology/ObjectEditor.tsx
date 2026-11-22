import { Alert, Button } from 'antd';
import { useEffect, useState } from 'react';
import {
  ontologyApi,
  type Columns,
  type Datasets,
  type ObjectType,
  type Property,
} from '../../api/ontologyApi';

const newProperty = (): Property => ({
  id: crypto.randomUUID(),
  name: '',
  columnName: '',
  declaredType: null,
});
export function ObjectEditor({
  environmentId,
  original,
  busy,
  candidateVersion,
  deploymentChanged,
  onChange,
  onSave,
  onClose,
}: {
  environmentId: string;
  original?: ObjectType;
  busy: boolean;
  candidateVersion: number;
  deploymentChanged: boolean;
  onChange: () => void;
  onClose: () => void;
  onSave: (value: ObjectType, buildId: string | null) => void;
}) {
  const [draft, setDraft] = useState<ObjectType>(
    () =>
      original || {
        id: crypto.randomUUID(),
        name: '',
        description: '',
        datasetAssetId: '',
        primaryPropertyId: '',
        properties: [newProperty()],
      },
  );
  const [candidates, setCandidates] = useState<Datasets>();
  const [columns, setColumns] = useState<Columns>();
  const [datasetError, setDatasetError] = useState('');
  const [columnError, setColumnError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const { datasetAssetId } = draft;
  useEffect(() => {
    let active = true;
    setCandidates(undefined);
    setDatasetError('');
    ontologyApi
      .datasets(environmentId)
      .then((value) => {
        if (active) setCandidates(value);
      })
      .catch((reason) => {
        if (active) setDatasetError(reason.message);
      });
    return () => {
      active = false;
    };
  }, [environmentId, attempt, candidateVersion]);
  useEffect(() => {
    let active = true;
    setColumns(undefined);
    setColumnError('');
    if (candidates?.buildId && candidates.datasets.some((d) => d.assetId === datasetAssetId)) {
      ontologyApi
        .columns(environmentId, datasetAssetId, candidates.buildId)
        .then((value) => {
          if (active && value.assetId === datasetAssetId && value.buildId === candidates.buildId)
            setColumns(value);
        })
        .catch((reason) => {
          if (active) setColumnError(reason.message);
        });
    }
    return () => {
      active = false;
    };
  }, [environmentId, datasetAssetId, candidates]);
  const available =
    columns && columns.assetId === datasetAssetId && columns.buildId === candidates?.buildId
      ? columns
      : undefined;
  function update(changes: Partial<ObjectType>) {
    onChange();
    setDraft({ ...draft, ...changes });
  }
  function changeProperty(id: string, changes: Partial<Property>) {
    update({ properties: draft.properties.map((p) => (p.id === id ? { ...p, ...changes } : p)) });
  }
  const changedBindings = draft.properties.filter((p) => {
    const old = original?.properties.find((previous) => previous.id === p.id);
    return (
      datasetAssetId !== original?.datasetAssetId ||
      (p.id === draft.primaryPropertyId && p.id !== original?.primaryPropertyId) ||
      old?.columnName !== p.columnName ||
      old?.declaredType !== p.declaredType
    );
  });
  const bindingsReady = changedBindings.every((p) =>
    available?.columns.some((c) => c.name === p.columnName && c.declaredType === p.declaredType),
  );
  const valid =
    draft.name.trim() &&
    datasetAssetId &&
    draft.primaryPropertyId &&
    draft.properties.some((p) => p.id === draft.primaryPropertyId) &&
    draft.properties.every((p) => p.name.trim() && p.columnName) &&
    bindingsReady &&
    !(deploymentChanged && changedBindings.length);
  return (
    <form
      className="ontology-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (valid) onSave(draft, changedBindings.length ? available!.buildId : null);
      }}
    >
      <fieldset disabled={busy}>
        {(datasetError || columnError) && (
          <Alert
            type="warning"
            title={datasetError || columnError}
            action={<Button onClick={() => setAttempt((n) => n + 1)}>重读候选</Button>}
          />
        )}
        {candidates && !candidates.datasets.length && (
          <Alert type="info" title="此环境尚无可绑定的数据集" />
        )}
        <div className="ontology-fields">
          <label>
            绑定数据集
            <select
              required
              value={datasetAssetId}
              onChange={(e) =>
                update({
                  datasetAssetId: e.target.value,
                  primaryPropertyId: '',
                  properties: draft.properties.map((p) => ({
                    ...p,
                    columnName: '',
                    declaredType: null,
                  })),
                })
              }
            >
              <option value="">
                {!candidates && !datasetError ? '正在读取数据集…' : '选择已部署数据集'}
              </option>
              {datasetAssetId &&
                !candidates?.datasets.some((d) => d.assetId === datasetAssetId) && (
                  <option value={datasetAssetId}>{datasetAssetId}（原绑定）</option>
                )}
              {candidates?.datasets.map((d) => (
                <option key={d.assetId} value={d.assetId}>
                  {d.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            对象名称
            <input
              required
              placeholder="例如：订单"
              value={draft.name}
              onChange={(e) => update({ name: e.target.value })}
            />
          </label>
          <label>
            对象说明
            <input
              value={draft.description ?? ''}
              onChange={(e) => update({ description: e.target.value })}
            />
          </label>
        </div>
        <h3>属性映射</h3>
        {draft.properties.map((p, index) => {
          const current = available?.columns.find((c) => c.name === p.columnName);
          return (
            <div className="ontology-property" key={p.id}>
              <label>
                属性名称 {index + 1}
                <input
                  required
                  value={p.name}
                  onChange={(e) => changeProperty(p.id, { name: e.target.value })}
                />
              </label>
              <label>
                绑定列 {index + 1}
                <select
                  required
                  value={p.columnName}
                  onChange={(e) => {
                    const column = available?.columns.find((c) => c.name === e.target.value);
                    changeProperty(p.id, {
                      name: p.name || column?.name || '',
                      columnName: column?.name || '',
                      declaredType: column?.declaredType ?? null,
                    });
                  }}
                >
                  <option value="">{!datasetAssetId ? '请先选择数据集' : '选择声明列'}</option>
                  {p.columnName && !current && (
                    <option value={p.columnName}>{p.columnName}（原绑定）</option>
                  )}
                  {available?.columns.map((c) => (
                    <option key={c.name} value={c.name}>
                      {c.name} · {c.declaredType || '类型未核实'}
                    </option>
                  ))}
                </select>
              </label>
              <Button
                disabled={draft.properties.length === 1}
                aria-label={'移除属性 ' + (index + 1)}
                onClick={() =>
                  update({
                    properties: draft.properties.filter((v) => v.id !== p.id),
                    primaryPropertyId:
                      draft.primaryPropertyId === p.id ? '' : draft.primaryPropertyId,
                  })
                }
              >
                移除
              </Button>
              {current?.declaredType && current.declaredType !== p.declaredType && (
                <Button
                  onClick={() => changeProperty(p.id, { declaredType: current.declaredType })}
                >
                  采用当前类型 {current.declaredType}
                </Button>
              )}
            </div>
          );
        })}
        <Button onClick={() => update({ properties: [...draft.properties, newProperty()] })}>
          添加属性
        </Button>
        <label>
          声明主键
          <select
            required
            value={draft.primaryPropertyId}
            onChange={(e) => update({ primaryPropertyId: e.target.value })}
          >
            <option value="">选择单个主键属性</option>
            {draft.properties.map((p, i) => (
              <option key={p.id} value={p.id}>
                {p.name || '属性 ' + (i + 1)}
              </option>
            ))}
          </select>
        </label>
        <small>主键唯一性由管理者声明。</small>
        <div className="ontology-actions">
          <Button type="primary" htmlType="submit" loading={busy} disabled={!valid}>
            {candidateVersion && changedBindings.length ? '确认绑定并保存对象' : '保存对象'}
          </Button>
          <Button onClick={onClose}>取消编辑</Button>
        </div>
      </fieldset>
    </form>
  );
}
