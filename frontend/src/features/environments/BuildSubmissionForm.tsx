import { Alert, Button } from 'antd';
import { useEffect, useState } from 'react';
import type { BuildCommand, EnvironmentApi, ExecutionEnvironment } from '../../api/environmentApi';

const ON_SUCCESS = 'ON_SUCCESS';
export function BuildSubmissionForm({
  environment,
  api,
  onSubmitted,
}: {
  environment: ExecutionEnvironment;
  api: EnvironmentApi;
  onSubmitted: () => void;
}) {
  const [repository, setRepository] = useState('');
  const [branch, setBranch] = useState('');
  const [branches, setBranches] = useState<string[]>([]);
  const [loadingBranches, setLoadingBranches] = useState(true);
  const [branchError, setBranchError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [pending, setPending] = useState<BuildCommand | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setLoadingBranches(true);
    setBranchError('');
    setBranches([]);
    setBranch('');
    api
      .repositoryBranches()
      .then((result) => {
        if (!active) return;
        setRepository(result.repository);
        setBranches(result.branches);
        if (!result.branches.length) setBranchError('仓库暂无可用分支');
      })
      .catch((reason) => {
        if (active) setBranchError(reason instanceof Error ? reason.message : 'Git 分支读取失败');
      })
      .finally(() => {
        if (active) setLoadingBranches(false);
      });
    return () => {
      active = false;
    };
  }, [api, refresh]);
  async function submit(retry: boolean) {
    if (
      busy ||
      (!retry && (pending || loadingBranches || !repository || !branches.includes(branch)))
    )
      return;
    const command =
      retry && pending
        ? pending
        : ({
            repository,
            branchName: branch,
            expectedConfigVersion: environment.configVersion,
            deploymentPolicy: ON_SUCCESS,
            idempotencyKey: crypto.randomUUID(),
          } as BuildCommand);
    setPending(command);
    setBusy(true);
    setError('');
    try {
      await api.submitBuild(environment.environmentId, command);
      setPending(null);
      onSubmitted();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '提交结果未知，请重试本次提交');
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="environment-form"
      onSubmit={(event) => {
        event.preventDefault();
        void submit(false);
      }}
    >
      <label>
        Git 仓库地址
        <input required type="url" readOnly value={repository} />
      </label>
      <label>
        构建分支
        <select
          required
          disabled={busy || !!pending || loadingBranches || !branches.length}
          value={branch}
          onChange={(event) => setBranch(event.target.value)}
        >
          <option value="">{loadingBranches ? '正在加载分支…' : '请选择分支'}</option>
          {branches.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </label>
      <Button
        disabled={busy || !!pending || loadingBranches}
        onClick={() => setRefresh((value) => value + 1)}
      >
        刷新分支
      </Button>
      {branchError && <Alert type="error" title={branchError} />}
      <p>构建成功后记录部署。同一环境的同名表可能被更新。</p>
      {error && <Alert type="error" title={error} />}
      {pending && error ? (
        <>
          <Button disabled={busy} onClick={() => void submit(true)}>
            重试本次提交
          </Button>
          <Button
            disabled={busy}
            onClick={() => {
              setPending(null);
              setError('');
            }}
          >
            开始新的提交
          </Button>
        </>
      ) : (
        <Button
          type="primary"
          htmlType="submit"
          loading={busy}
          disabled={loadingBranches || !repository || !branches.includes(branch)}
        >
          发起构建
        </Button>
      )}
    </form>
  );
}
