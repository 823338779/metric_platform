import { Alert, Button, Modal, Spin } from 'antd';
import {
  ApartmentOutlined,
  ArrowLeftOutlined,
  DatabaseOutlined,
  UnorderedListOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { Link, useBlocker, useParams } from 'react-router-dom';
import { getAuthSession } from '../../api/auth';
import { environmentApi } from '../../api/environmentApi';
import { ApiProblem } from '../../api/httpClient';
import { ontologyApi, type Topology } from '../../api/ontologyApi';
import { ObjectEditor } from './ObjectEditor';
import { RelationEditor } from './RelationEditor';
import { Health } from './BindingHealth';
import { DefinitionLibrary } from './DefinitionLibrary';
import { OntologyGraph } from './OntologyGraph';
import './ontology.css';

type Selection = { kind: 'object' | 'relation'; id?: string; deleting?: boolean };
export function OntologyPage() {
  const { environmentId = '' } = useParams();
  if (getAuthSession()?.identity.role !== 'TENANT_MANAGER')
    return <Alert type="warning" title="仅租户管理者可维护业务本体" />;
  return <EnvironmentOntology key={environmentId} environmentId={environmentId} />;
}

function EnvironmentOntology({ environmentId }: { environmentId: string }) {
  const [name, setName] = useState('');
  const [graph, setGraph] = useState<Topology>();
  const forceObservation = useRef(false);
  const [refresh, setRefresh] = useState(0);
  const [loadError, setLoadError] = useState('');
  const [loading, setLoading] = useState(true);
  const [selection, setSelection] = useState<Selection>();
  const [pendingSelection, setPendingSelection] = useState<Selection | 'close'>();
  const [search, setSearch] = useState('');
  const [libraryOpen, setLibraryOpen] = useState(false);
  const discardButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (pendingSelection) discardButton.current?.focus();
  }, [pendingSelection]);

  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [failure, setFailure] = useState<Error>();
  const [candidateVersion, setCandidateVersion] = useState(0);
  const [candidateError, setCandidateError] = useState('');
  const blocker = useBlocker(() => (dirty || busy) && !!getAuthSession());
  useEffect(() => {
    if (blocker.state === 'blocked' && !dirty && !busy && !failure) blocker.proceed();
  }, [blocker, dirty, busy, failure]);
  useEffect(() => {
    if (!dirty && !busy) return;
    const guard = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [dirty, busy]);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError('');
    const environment = environmentApi.get(environmentId).then((e) => {
      if (active) setName(e.name);
    });
    const topology = forceObservation.current
      ? ontologyApi.get(environmentId, true)
      : ontologyApi.get(environmentId);
    forceObservation.current = false;
    Promise.all([environment, topology])
      .then(([, g]) => {
        if (active) setGraph(g);
      })
      .catch((reason) => {
        if (active) setLoadError(reason.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [environmentId, refresh]);
  function open(next?: Selection) {
    setSelection(next);
    setPendingSelection(undefined);
    setFailure(undefined);
    setDirty(false);
    setCandidateVersion(0);
    setCandidateError('');
  }
  function select(kind: Selection['kind'], id?: string, deleting = false) {
    if (
      pending.current ||
      (selection?.id === id && selection?.kind === kind && !!selection.deleting === deleting)
    )
      return;
    const next = { kind, id, deleting };
    if (dirty) setPendingSelection(next);
    else open(next);
  }
  function close() {
    if (pending.current || pendingSelection || blocker.state === 'blocked') return;
    if (!selection) setLibraryOpen(false);
    if (dirty) setPendingSelection('close');
    else open();
  }
  function reload() {
    setLibraryOpen(false);
    open();
    setRefresh((value) => value + 1);
  }
  function refreshEvidence() {
    forceObservation.current = true;
    reload();
  }
  async function save(write: () => Promise<unknown>) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setFailure(undefined);
    try {
      await write();
      setGraph(undefined);
      reload();
    } catch (reason) {
      setFailure(reason instanceof Error ? reason : new Error('本体保存失败'));
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  const object = graph?.objects.find((o) => o.id === selection?.id);
  const relation = graph?.relations.find((r) => r.id === selection?.id);
  const selected = selection?.kind === 'object' ? object : relation;
  const blockers =
    selection?.kind === 'object' && selection.deleting
      ? (graph?.relations.filter(
          (r) => r.sourceObjectId === object?.id || r.targetObjectId === object?.id,
        ) ?? [])
      : [];
  const problem = failure instanceof ApiProblem ? failure.problem : undefined;
  const deploymentChanged = problem?.code === 'DEPLOYMENT_CHANGED';
  async function refreshCandidates() {
    if (pending.current || !graph) return;
    pending.current = true;
    setBusy(true);
    setCandidateError('');
    try {
      const latest = await ontologyApi.get(environmentId);
      if (latest.revision !== graph.revision) {
        setFailure(
          new ApiProblem({ status: 409, title: '编辑冲突', detail: '本体已被修改，请刷新后重试' }),
        );
        return;
      }
      setGraph(latest);
      setCandidateVersion((value) => value + 1);
      setFailure(undefined);
    } catch (reason) {
      setCandidateError(reason instanceof Error ? reason.message : '读取当前候选失败');
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="environment-page ontology-page">
      <Link className="back-link" to={'/dbt/environments/' + environmentId}>
        <ArrowLeftOutlined aria-hidden /> 返回环境详情
      </Link>
      <header className="page-heading">
        <div className="ontology-title">
          <h2>本体建模</h2>
          <span className="ontology-environment">
            <DatabaseOutlined aria-hidden />
            {name || environmentId}
          </span>
        </div>
        <div className="ontology-actions">
          <Button icon={<ReloadOutlined aria-hidden />} loading={loading} onClick={reload}>
            刷新拓扑
          </Button>
          <Button disabled={busy || dirty || loading} onClick={refreshEvidence}>
            重新核实
          </Button>
          {graph && !loadError && graph.objects.length > 0 && (
            <>
              <Button
                icon={<UnorderedListOutlined aria-hidden />}
                disabled={loading}
                onClick={() => setLibraryOpen(true)}
              >
                管理定义
              </Button>
              <Button
                disabled={loading || !graph.objects.length}
                onClick={() => select('relation')}
              >
                新建关系
              </Button>
              <Button
                type="primary"
                icon={<PlusOutlined aria-hidden />}
                disabled={loading}
                onClick={() => select('object')}
              >
                新建对象
              </Button>
            </>
          )}
        </div>
      </header>
      {loadError && <Alert type="error" title={'无法读取本体：' + loadError} />}
      {loading && (
        <div className="ontology-loading" role="status">
          <Spin size="small" />
          正在核对当前环境的绑定…
        </div>
      )}
      {graph && !loading && !loadError && (
        <>
          <Health findings={graph.bindingReport.findings.filter((f) => f.kind === 'ENVIRONMENT')} />
          <div className="ontology-workspace">
            <section className="ontology-canvas">
              <div className="ontology-section-heading">
                <h3>
                  <ApartmentOutlined aria-hidden /> 对象关系拓扑
                </h3>
                <span>
                  {graph.objects.length} 个对象 · {graph.relations.length} 条关系
                </span>
              </div>
              {graph.objects.length ? (
                <>
                  <OntologyGraph
                    objects={graph.objects}
                    relations={graph.relations}
                    onSelect={select}
                    selected={selection}
                  />
                  <p className="ontology-canvas-help">
                    点击对象或连线编辑定义，拖动画布平移，滚轮缩放。
                  </p>
                </>
              ) : (
                <div className="ontology-canvas-empty">
                  <ApartmentOutlined aria-hidden />
                  <h4>暂无对象</h4>
                  <p>选择此环境已部署的数据集，为第一个对象绑定属性和主键。</p>
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => select('object')}>
                    新建对象
                  </Button>
                </div>
              )}
            </section>
          </div>
          {(selection || libraryOpen) && (
            <Modal
              open
              className="ontology-modal"
              title={
                selection
                  ? (selection.deleting ? '删除' : selection.id ? '编辑' : '新建') +
                    (selection.kind === 'object' ? '对象' : '关系')
                  : '管理定义'
              }
              width={selection?.deleting ? 460 : 680}
              footer={null}
              onCancel={close}
              closable={!busy}
              keyboard={!busy}
              mask={{ closable: false }}
            >
              {blocker.state === 'blocked' && (
                <div role="alertdialog" aria-label="放弃未保存的修改" className="ontology-notice">
                  <p>{busy ? '正在等待服务器响应。' : '离开后将放弃此环境中未保存的修改。'}</p>
                  <div className="ontology-actions">
                    <Button onClick={() => blocker.reset()}>留在此页</Button>
                    {!busy && (
                      <Button danger onClick={() => blocker.proceed()}>
                        放弃并离开
                      </Button>
                    )}
                  </div>
                </div>
              )}
              {failure && (
                <Alert
                  type="error"
                  title={failure.message}
                  description={
                    <>
                      {problem?.references?.length ? (
                        <p>引用此项的关系：{problem.references.map((r) => r.name).join('、')}</p>
                      ) : null}
                      {problem?.status === 409 && !deploymentChanged && (
                        <div>
                          请核对最新的拓扑及部署后再提交。{' '}
                          <Button onClick={refreshEvidence}>放弃本次编辑并刷新</Button>
                        </div>
                      )}
                    </>
                  }
                />
              )}
              {selection &&
                !selection.deleting &&
                (deploymentChanged ||
                  (selection.kind === 'relation' && !graph?.bindingReport.buildId)) && (
                  <div className="ontology-actions">
                    <Button loading={busy} onClick={refreshCandidates}>
                      刷新绑定候选
                    </Button>
                    {candidateError && <span role="alert">{candidateError}</span>}
                  </div>
                )}
              {selection ? (
                <div className="ontology-editor">
                  {pendingSelection && (
                    <div className="ontology-notice" role="alertdialog" aria-label="放弃当前编辑">
                      <p>当前定义有未保存的修改。</p>
                      <div className="ontology-actions">
                        <Button ref={discardButton} onClick={() => setPendingSelection(undefined)}>
                          继续编辑
                        </Button>
                        <Button
                          danger
                          onClick={() =>
                            open(pendingSelection === 'close' ? undefined : pendingSelection)
                          }
                        >
                          放弃修改
                        </Button>
                      </div>
                    </div>
                  )}
                  {selection.deleting && selected ? (
                    <div role="alertdialog" aria-label="删除确认">
                      <p>确定删除“{selected.name}”？</p>
                      {blockers.length > 0 ? (
                        <p>请先处理引用此对象的关系：{blockers.map((r) => r.name).join('、')}</p>
                      ) : (
                        <p>删除将在当前环境立即生效。</p>
                      )}
                      <div className="ontology-actions">
                        <Button
                          danger
                          disabled={!!blockers.length}
                          loading={busy}
                          onClick={() =>
                            save(() =>
                              ontologyApi.remove(
                                environmentId,
                                selection.kind === 'object' ? 'objects' : 'relations',
                                selected.id,
                                graph.revision,
                              ),
                            )
                          }
                        >
                          确认删除
                        </Button>
                        <Button disabled={busy} onClick={close}>
                          取消删除
                        </Button>
                      </div>
                    </div>
                  ) : selection.kind === 'object' ? (
                    <ObjectEditor
                      key={selection.id ?? 'new'}
                      environmentId={environmentId}
                      original={object}
                      busy={busy}
                      candidateVersion={candidateVersion}
                      deploymentChanged={deploymentChanged}
                      onChange={() => setDirty(true)}
                      onClose={close}
                      onSave={(value, buildId) =>
                        save(() =>
                          ontologyApi.saveObject(
                            environmentId,
                            graph.revision,
                            buildId,
                            value,
                            !object,
                          ),
                        )
                      }
                    />
                  ) : (
                    <RelationEditor
                      key={selection.id ?? 'new'}
                      objects={graph.objects}
                      original={relation}
                      buildId={graph.bindingReport.buildId}
                      busy={busy}
                      refreshed={candidateVersion > 0}
                      deploymentChanged={deploymentChanged}
                      onChange={() => setDirty(true)}
                      onClose={close}
                      onSave={(value, buildId) =>
                        save(() =>
                          ontologyApi.saveRelation(
                            environmentId,
                            graph.revision,
                            buildId,
                            value,
                            !relation,
                          ),
                        )
                      }
                    />
                  )}
                  {selected &&
                    !selection.deleting &&
                    graph.bindingReport.findings
                      .filter((f) =>
                        selection.kind === 'relation'
                          ? f.kind === 'RELATION' && f.id === selected.id
                          : f.kind === 'PROPERTY' && object?.properties.some((p) => p.id === f.id),
                      )
                      .map((f) => {
                        const baseline = f.evidence?.acceptedPhysicalType;
                        const observed = f.evidence?.lastObservation;
                        return (
                          <div key={f.id}>
                            <Health findings={[f]} />
                            {object &&
                              f.id &&
                              f.reason === 'TYPE_CONFLICT' &&
                              f.evidence?.typeSource === 'WAREHOUSE' &&
                              baseline &&
                              observed &&
                              baseline !== observed.physicalType && (
                                <Button
                                  disabled={busy || dirty}
                                  onClick={() =>
                                    save(() =>
                                      ontologyApi.saveObject(
                                        environmentId,
                                        graph.revision,
                                        graph.bindingReport.buildId,
                                        object,
                                        false,
                                        [
                                          {
                                            propertyId: f.id!,
                                            expectedBaselineType: baseline,
                                            observedType: observed.physicalType,
                                          },
                                        ],
                                      ),
                                    )
                                  }
                                >
                                  接受当前物理类型
                                </Button>
                              )}
                          </div>
                        );
                      })}
                  {selected && !selection.deleting && (
                    <Button
                      type="text"
                      danger
                      disabled={busy}
                      onClick={() => select(selection.kind, selected.id, true)}
                    >
                      删除{selection.kind === 'object' ? '对象' : '关系'}
                    </Button>
                  )}
                </div>
              ) : (
                <DefinitionLibrary
                  graph={graph}
                  search={search}
                  onSearch={setSearch}
                  onSelect={select}
                />
              )}
            </Modal>
          )}
        </>
      )}
    </section>
  );
}
