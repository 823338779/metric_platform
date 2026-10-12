import { Alert, Button } from 'antd';
import { useState } from 'react';
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
  const [pending, setPending] = useState<BuildCommand | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(retry: boolean) {
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
        <input
          required
          type="url"
          disabled={busy || !!pending}
          value={repository}
          onChange={(event) => setRepository(event.target.value)}
        />
      </label>
      <label>
        构建分支
        <input
          required
          disabled={busy || !!pending}
          value={branch}
          onChange={(event) => setBranch(event.target.value)}
        />
      </label>
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
        <Button type="primary" htmlType="submit" loading={busy}>
          发起构建
        </Button>
      )}
    </form>
  );
}
