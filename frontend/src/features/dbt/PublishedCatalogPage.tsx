import { Alert, Button, Skeleton } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { publishedCatalogApi, type PublishedCatalogApi } from '../../api/publishedCatalogApi';
import { DbtCatalogPage } from './DbtCatalogPage';
import { publishedResourceAdapter } from './publishedResourceAdapter';

const COPY = {
  refresh: '刷新已发布版本',
  failure: '发布版本读取失败，请重试',
  empty: '项目尚未发布',
};

export function PublishedCatalogPage({ api = publishedCatalogApi }: { api?: PublishedCatalogApi }) {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const releaseId = params.get('releaseId');
  const adapter = useMemo(() => publishedResourceAdapter(api), [api]);
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    return () => {
      generation.current++;
    };
  }, [projectId, releaseId]);
  const refresh = async () => {
    const turn = ++generation.current;
    setBusy(true);
    setError('');
    try {
      const value = await api.publication(projectId);
      if (turn !== generation.current) return;
      const version = value.activePublication?.releaseId;
      if (!version) {
        setError(COPY.empty);
        return;
      }
      setParams((current) => {
        const next = new URLSearchParams(current);
        next.set('releaseId', version);
        if (version !== releaseId) next.delete('resourceId');
        return next;
      });
      setAttempt((value) => value + 1);
    } catch {
      if (turn === generation.current) setError(COPY.failure);
    } finally {
      if (turn === generation.current) setBusy(false);
    }
  };
  useEffect(() => {
    if (!releaseId) void refresh();
  }, [projectId, releaseId]);
  const action = (
    <Button size="small" icon={<ReloadOutlined />} loading={busy} onClick={() => void refresh()}>
      {COPY.refresh}
    </Button>
  );
  return (
    <div className="published-catalog">
      {error && <Alert title={error} type="warning" />}
      {releaseId ? (
        <DbtCatalogPage
          key={`${projectId}:${releaseId}:${attempt}`}
          api={adapter}
          actions={action}
        />
      ) : (
        <>
          {action}
          {!error && <Skeleton active />}
        </>
      )}
    </div>
  );
}
