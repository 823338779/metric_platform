import { Alert } from 'antd';
import { useEffect, useState } from 'react';
import type { BranchContext, BranchDiff, PublishedCatalogApi } from '../../api/publishedCatalogApi';

/** 差异按完整上下文读取，旧异步响应不覆盖新分支。 */
export function BranchChangesPanel({
  api,
  context,
}: {
  api: PublishedCatalogApi;
  context: BranchContext;
}) {
  const [value, setValue] = useState<BranchDiff | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setValue(null);
    setError('');
    if (!context.releaseId) return;
    api
      .diff(context)
      .then((result) => {
        if (!active) return;
        if (result.branchId !== context.branchId || result.releaseId !== context.releaseId)
          throw new Error('差异归属不一致');
        setValue(result);
      })
      .catch(() => {
        if (active) setError('此版本差异暂不可用，请刷新后重试');
      });
    return () => {
      active = false;
    };
  }, [api, context.projectId, context.branchId, context.releaseId]);
  return (
    <details>
      <summary>相对创建基线的变更</summary>
      {error && <Alert type="warning" title={error} />}
      {value && (
        <>
          <p>
            基线 <code>{value.baseCommitSha ?? '不可用'}</code> → 目标{' '}
            <code>{value.targetCommitSha}</code>
          </p>
          {value.productionAdvanced && (
            <Alert type="info" title="线上已推进，当前对比仍使用创建时基线" />
          )}
          {!value.resourceBaselineAvailable && (
            <Alert type="warning" title="资源基线不可用，仅展示可取得的源码差异" />
          )}
          {value.truncated && <Alert type="warning" title="差异已截断，不能作为完整审阅材料" />}
          {value.files.map((file) => (
            <details key={file.path}>
              <summary>
                {file.status} {file.path}
              </summary>
              <pre>{file.diff}</pre>
            </details>
          ))}
          <ul>
            {value.resources.map((resource) => (
              <li key={`${resource.kind}:${resource.nativeId}`}>
                {resource.status} · {resource.kind} · {resource.nativeId}
              </li>
            ))}
          </ul>
        </>
      )}
    </details>
  );
}
