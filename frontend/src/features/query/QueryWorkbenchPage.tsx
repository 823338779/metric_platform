import { Alert, Button, Card, Space, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type {
  DimensionSelection,
  QueryConnectivityResponse,
  QueryRequest,
  QueryResponse,
  SemanticSearchResult,
  TimeGranularity,
  ValidationResponse,
} from '../../api/types';
import { PlanExplanation } from './PlanExplanation';
import { classifySemanticObjects } from './queryCompatibility';
import { QueryResultTable } from './QueryResultTable';
import { exportQueryResultCsv } from './queryResultCsv';
import { SemanticObjectPicker } from './SemanticObjectPicker';
import { SemanticSelectionFlow } from './SemanticSelectionFlow';
import { placeSelection } from './semanticSelection';

type QueryWorkbenchApi = Pick<
  typeof metricPlatformApi,
  'searchSemantic' | 'getQueryConnectivity' | 'resolveSemantic' | 'validateQuery' | 'executeQuery'
>;

const granularityOrder: TimeGranularity[] = ['DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR'];
const refreshIssueCodes = new Set([
  'RELATIONSHIP_PATH_NOT_FOUND',
  'AMBIGUOUS_RELATIONSHIP_PATH',
  'SEMANTIC_OBJECT_NOT_FOUND',
]);

export function QueryWorkbenchPage({ api = metricPlatformApi }: { api?: QueryWorkbenchApi } = {}) {
  const [metricIds, setMetricIds] = useState<string[]>([]);
  const [dimensions, setDimensionSelections] = useState<DimensionSelection[]>([]);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SemanticSearchResult[]>([]);
  const [catalog, setCatalog] = useState<Record<string, SemanticSearchResult>>({});
  const [connectivity, setConnectivity] = useState<QueryConnectivityResponse>();
  const [catalogRevision, setCatalogRevision] = useState(0);
  const [granularities, setGranularities] = useState<Record<string, TimeGranularity[]>>({});
  const [searchLoading, setSearchLoading] = useState(false);
  const [plan, setPlan] = useState<ValidationResponse>();
  const [result, setResult] = useState<QueryResponse>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const requestId = useRef(0);
  const dimensionIds = useMemo(() => dimensions.map((value) => value.dimensionId), [dimensions]);
  const request = useMemo<QueryRequest>(
    () => ({ metricIds, dimensions, relationshipIds: [] }),
    [metricIds, dimensions],
  );

  const reloadCatalogSnapshot = useCallback(
    async (refreshSemantic = false) => {
      const value = await api.getQueryConnectivity();
      setConnectivity(value);
      if (refreshSemantic) setCatalogRevision((current) => current + 1);
    },
    [api],
  );

  useEffect(() => {
    let cancelled = false;
    void api.getQueryConnectivity().then(
      (value) => {
        if (!cancelled) setConnectivity(value);
      },
      (reason) => {
        if (!cancelled) setError(errorMessage(reason, '连通性加载失败'));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    const current = ++requestId.current;
    const timer = window.setTimeout(() => {
      setSearchLoading(true);
      void api
        .searchSemantic(query)
        .then((values) => {
          if (current !== requestId.current) return;
          const semanticValues = values.filter(
            (value) => value.type === 'METRIC' || value.type === 'DIMENSION',
          );
          setResults(semanticValues);
          setCatalog((previous) => ({
            ...previous,
            ...Object.fromEntries(semanticValues.map((value) => [value.id, value])),
          }));
        })
        .catch((reason) => {
          if (current === requestId.current) setError(errorMessage(reason, '语义对象加载失败'));
        })
        .finally(() => {
          if (current === requestId.current) setSearchLoading(false);
        });
    }, 200);
    return () => window.clearTimeout(timer);
  }, [api, catalogRevision, query]);

  const compatibility = useMemo(
    () =>
      connectivity
        ? classifySemanticObjects(Object.values(catalog), metricIds, dimensionIds, connectivity)
        : {},
    [catalog, connectivity, dimensionIds, metricIds],
  );

  const clearOutput = () => {
    setPlan(undefined);
    setResult(undefined);
    setError('');
  };
  const changeMetrics = (ids: string[]) => {
    clearOutput();
    setMetricIds(ids);
  };
  const changeDimensions = (value: DimensionSelection[]) => {
    clearOutput();
    setDimensionSelections(value);
  };
  const placeMetric = (id: string, index = metricIds.length) =>
    changeMetrics(placeSelection(metricIds, id, index));
  const placeDimension = async (id: string, index = dimensions.length) => {
    clearOutput();
    const semantic = catalog[id];
    let granularity: TimeGranularity | undefined;
    if (semantic?.summary?.dimensionType === 'TIME') {
      try {
        const resolved = await api.resolveSemantic({
          objects: [{ type: 'DIMENSION', id }],
          relationshipIds: [],
        });
        const available = new Set(
          resolved.objects.find((value) => value.type === 'DIMENSION' && value.id === id)
            ?.allowedGranularities ?? [],
        );
        const allowed = granularityOrder.filter((value) => available.has(value));
        granularity = allowed[0];
        setGranularities((current) => ({ ...current, [id]: allowed }));
      } catch (reason) {
        setError(errorMessage(reason, '时间维度粒度加载失败'));
        return;
      }
    }
    const next = dimensions.filter((value) => value.dimensionId !== id);
    next.splice(Math.max(0, Math.min(index, next.length)), 0, { dimensionId: id, granularity });
    setDimensionSelections(next);
  };
  const addObject = (value: SemanticSearchResult) => {
    if (value.type === 'METRIC') placeMetric(value.id);
    if (value.type === 'DIMENSION') void placeDimension(value.id);
  };

  const execute = async () => {
    if (!metricIds.length) return;
    setLoading(true);
    setError('');
    try {
      const validated = await api.validateQuery(request);
      setPlan(validated);
      if (!validated.valid) {
        setResult(undefined);
        const issue = validated.issues[0];
        setError(issue?.message ?? '当前选择无法执行查询');
        if (validated.issues.some((value) => refreshIssueCodes.has(value.code))) {
          await reloadCatalogSnapshot(true);
        }
        return;
      }
      setResult(await api.executeQuery(validated.normalizedRequest ?? request));
    } catch (reason) {
      setError(errorMessage(reason, '查询失败'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Card title="查询工作台" className="query-workbench-card">
        <Typography.Paragraph type="secondary" className="query-workbench-intro">
          选择指标和维度后，页面会根据数据集关系在本地判断可组合对象；执行时再进行最终校验。
        </Typography.Paragraph>
        <div className="query-workbench-grid">
          <SemanticObjectPicker
            objects={results}
            compatibility={compatibility}
            query={query}
            loading={searchLoading}
            connectivityReady={Boolean(connectivity)}
            onQuery={setQuery}
            onAdd={addObject}
          />
          <div className="query-workspace">
            <Card size="small" title="构建指标查询" className="query-builder-card">
              <SemanticSelectionFlow
                metricIds={metricIds}
                dimensions={dimensions}
                catalog={catalog}
                granularities={granularities}
                onMetrics={changeMetrics}
                onDimensions={changeDimensions}
                onPlaceMetric={placeMetric}
                onPlaceDimension={(id, index) => void placeDimension(id, index)}
              />
            </Card>
            {error && <Alert type="error" showIcon message="查询无法完成" description={error} />}
            {plan && <PlanExplanation plan={plan} sql={result?.sql} />}
            <Card
              size="small"
              title={
                <Space size="small">
                  <span>查询结果</span>
                  {result && <Tag color="blue">耗时 {result.durationMs} ms</Tag>}
                </Space>
              }
              className="query-result-card"
              extra={
                <Space size="small">
                  <Button
                    size="small"
                    aria-label="刷新"
                    disabled={!result || loading}
                    onClick={() => void execute()}
                  >
                    刷新
                  </Button>
                  <Button
                    size="small"
                    aria-label="导出"
                    disabled={!result || loading}
                    onClick={() => result && exportQueryResultCsv(result)}
                  >
                    导出
                  </Button>
                  <Button
                    type="primary"
                    size="small"
                    aria-label="执行查询"
                    disabled={!metricIds.length || loading || !connectivity}
                    loading={loading}
                    onClick={() => void execute()}
                  >
                    执行查询
                  </Button>
                </Space>
              }
            >
              <QueryResultTable result={result} loading={loading} />
            </Card>
          </div>
        </div>
      </Card>
    </Space>
  );
}

function errorMessage(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback;
}
