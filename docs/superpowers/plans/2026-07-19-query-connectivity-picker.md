# Query Connectivity-Aware Object Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load query connectivity once, calculate semantic-object compatibility locally, and implement the approved compact left-sidebar query workbench without selection-time validation requests.

**Architecture:** The backend exposes a catalog-versioned directed reachability matrix generated with the same `DatasetRelationshipGraph` used by query planning. The frontend combines that matrix with each semantic object's `datasetId`, computes compatibility through pure functions, sorts addable objects first, and keeps `/queries:validate` only as the final pre-execution guard.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, JUnit 5, Mockito, OpenAPI 3.1, React 19, TypeScript 5.8, Ant Design 6, Vitest, Testing Library

## Global Constraints

- Modify only the `metric-platform` project; do not change Data Agent.
- Do not send a connectivity or validation request when a user adds, removes, or reorders an object.
- Preserve the planner's same-data-source, directed, unique, non-expanding path semantics.
- Disable ambiguous and missing paths; relationship-path selection remains out of scope.
- Do not render “全部”, “可添加”, “不可添加”, “可查询”, or “已重算” controls or status text.
- Sort each library as addable objects, selected objects, then incompatible objects.
- Metrics use pale green; dimensions use blue.
- Keep the left object sidebar approximately 210 pixels wide with compact rows.
- Place `执行查询`, `刷新`, and `导出` in the query-result header.
- Do not add result-limit, sort, or filter controls.
- Do not create a Git commit unless the user explicitly requests one.

---

### Task 1: Backend Connectivity Model and Service

**Files:**
- Create: `backend/src/main/java/com/metricplatform/query/QueryConnectivityService.java`
- Modify: `backend/src/main/java/com/metricplatform/query/QueryDtos.java`
- Create: `backend/src/test/java/com/metricplatform/query/QueryConnectivityServiceTest.java`

**Interfaces:**
- Consumes: `DatasetEntityPersistenceService.list()`, `SemanticCatalogReader.relationships()`, `SemanticCatalogService.catalogVersion()`, and `DatasetRelationshipGraph.resolve(UUID, UUID, Set<UUID>)`.
- Produces: `QueryConnectivityService.connectivity(): QueryDtos.QueryConnectivityResponse`.

- [ ] **Step 1: Write the failing service tests**

Create `QueryConnectivityServiceTest` with Mockito-backed catalog fixtures. Use fixed UUIDs for an order dataset, customer dataset, region dataset, another data source, and two alternative paths. Assert:

```java
@ExtendWith(MockitoExtension.class)
class QueryConnectivityServiceTest {
    @Mock DatasetEntityPersistenceService datasets;
    @Mock SemanticCatalogReader catalog;
    @Mock SemanticCatalogService semanticCatalog;
    @InjectMocks QueryConnectivityService service;

    @Test
    void returnsDatasetSourcesAndDirectedUniquePaths() {
        when(datasets.list()).thenReturn(List.of(orderDataset(), customerDataset()));
        when(catalog.relationships()).thenReturn(List.of(orderCustomer()));
        when(semanticCatalog.catalogVersion()).thenReturn("catalog-7");

        QueryConnectivityResponse response = service.connectivity();

        assertThat(response.catalogVersion()).isEqualTo("catalog-7");
        assertThat(response.datasets())
                .extracting(DatasetConnectivity::datasetId, DatasetConnectivity::dataSourceId)
                .containsExactlyInAnyOrder(tuple(ORDER, SOURCE_A), tuple(CUSTOMER, SOURCE_A));
        assertThat(response.paths())
                .containsExactly(new ConnectivityPath(
                        ORDER, CUSTOMER, ConnectivityPathStatus.UNIQUE_PATH,
                        List.of(ORDER_CUSTOMER)));
    }

    @Test
    void omitsMissingAndReverseOnlyPaths() {
        when(datasets.list()).thenReturn(List.of(orderDataset(), customerDataset()));
        when(catalog.relationships()).thenReturn(List.of(orderCustomer()));
        when(semanticCatalog.catalogVersion()).thenReturn("catalog-7");

        assertThat(service.connectivity().paths())
                .noneMatch(path -> path.sourceDatasetId().equals(CUSTOMER)
                        && path.targetDatasetId().equals(ORDER));
    }

    @Test
    void reportsAmbiguousPathsWithoutSelectingOne() {
        when(datasets.list()).thenReturn(List.of(
                orderDataset(), customerDataset(), alternateDataset(), regionDataset()));
        when(catalog.relationships()).thenReturn(List.of(
                orderCustomer(), customerRegion(), orderAlternate(), alternateRegion()));
        when(semanticCatalog.catalogVersion()).thenReturn("catalog-8");

        ConnectivityPath path = service.connectivity().paths().stream()
                .filter(value -> value.sourceDatasetId().equals(ORDER)
                        && value.targetDatasetId().equals(REGION))
                .findFirst().orElseThrow();

        assertThat(path.status()).isEqualTo(ConnectivityPathStatus.AMBIGUOUS_PATH);
        assertThat(path.relationshipIds()).isEmpty();
    }
}
```

Fixture helpers must create `DatasetEntity` values with `id` and `dataSourceId`, plus `RelationshipDefinition` values using `MANY_TO_ONE` cardinality.

- [ ] **Step 2: Run the test and verify RED**

Run from `metric-platform/` with Java 21:

```powershell
$env:JAVA_HOME='E:\env\jdks\ms-21.0.11'
backend\mvnw.cmd -f backend\pom.xml -Dtest=QueryConnectivityServiceTest test
```

Expected: compilation fails because `QueryConnectivityService` and the connectivity DTOs do not exist.

- [ ] **Step 3: Add the exact DTO contract**

Add these nested types to `QueryDtos`:

```java
public enum ConnectivityPathStatus { UNIQUE_PATH, AMBIGUOUS_PATH }

public record DatasetConnectivity(UUID datasetId, UUID dataSourceId) {}

public record ConnectivityPath(
        UUID sourceDatasetId,
        UUID targetDatasetId,
        ConnectivityPathStatus status,
        List<UUID> relationshipIds) {
    public ConnectivityPath {
        relationshipIds = relationshipIds == null ? List.of() : List.copyOf(relationshipIds);
    }
}

public record QueryConnectivityResponse(
        String catalogVersion,
        List<DatasetConnectivity> datasets,
        List<ConnectivityPath> paths) {}
```

- [ ] **Step 4: Implement the service with the planner graph**

Implement `QueryConnectivityService` as a Spring `@Service`:

```java
@Service
@RequiredArgsConstructor
public class QueryConnectivityService {
    private final DatasetEntityPersistenceService datasets;
    private final SemanticCatalogReader catalog;
    private final SemanticCatalogService semanticCatalog;

    public QueryDtos.QueryConnectivityResponse connectivity() {
        List<DatasetEntity> activeDatasets = datasets.list();
        Set<UUID> datasetIds = activeDatasets.stream()
                .map(DatasetEntity::getId)
                .collect(Collectors.toUnmodifiableSet());
        List<RelationshipDefinition> relationships = catalog.relationships().stream()
                .filter(value -> datasetIds.contains(value.sourceDatasetId())
                        && datasetIds.contains(value.targetDatasetId()))
                .toList();
        DatasetRelationshipGraph graph = new DatasetRelationshipGraph(
                relationships.stream()
                        .map(value -> new RelationshipEdge(
                                value.id(), value.sourceDatasetId(), value.targetDatasetId(),
                                value.cardinality()))
                        .toList());

        List<QueryDtos.ConnectivityPath> paths = new ArrayList<>();
        for (DatasetEntity source : activeDatasets) {
            for (DatasetEntity target : activeDatasets) {
                if (source.getId().equals(target.getId())) continue;
                PathResolution resolution = graph.resolve(source.getId(), target.getId(), Set.of());
                if (resolution instanceof UniquePath unique) {
                    paths.add(new QueryDtos.ConnectivityPath(
                            source.getId(), target.getId(),
                            QueryDtos.ConnectivityPathStatus.UNIQUE_PATH,
                            unique.edges().stream().map(RelationshipEdge::id).toList()));
                } else if (resolution instanceof AmbiguousPaths) {
                    paths.add(new QueryDtos.ConnectivityPath(
                            source.getId(), target.getId(),
                            QueryDtos.ConnectivityPathStatus.AMBIGUOUS_PATH, List.of()));
                }
            }
        }
        return new QueryDtos.QueryConnectivityResponse(
                semanticCatalog.catalogVersion(),
                activeDatasets.stream()
                        .map(value -> new QueryDtos.DatasetConnectivity(
                                value.getId(), value.getDataSourceId()))
                        .toList(),
                List.copyOf(paths));
    }
}
```

Do not emit same-dataset or missing-path rows.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the Task 1 command again. Expected: all `QueryConnectivityServiceTest` tests pass.

- [ ] **Step 6: Review the Task 1 diff without committing**

```powershell
git diff --check -- backend/src/main/java/com/metricplatform/query backend/src/test/java/com/metricplatform/query
```

Expected: no whitespace errors.

---

### Task 2: Authenticated Connectivity Endpoint and OpenAPI Contract

**Files:**
- Modify: `backend/src/main/java/com/metricplatform/query/QueryController.java`
- Modify: `backend/src/test/java/com/metricplatform/query/QueryControllerTest.java`
- Modify: `api/openapi.yaml`
- Modify: `backend/src/test/java/com/metricplatform/api/OpenApiContractTest.java`

**Interfaces:**
- Consumes: `QueryConnectivityService.connectivity()`.
- Produces: authenticated `GET /api/v1/query-connectivity` and its OpenAPI schemas.

- [ ] **Step 1: Add failing controller and contract assertions**

Add these controller tests:

```java
@Test
void anonymousCannotReadQueryConnectivity() throws Exception {
    mvc.perform(get("/api/v1/query-connectivity"))
            .andExpect(status().isUnauthorized());
}

@Test
void authenticatedAccountCanReadQueryConnectivity() throws Exception {
    mvc.perform(get("/api/v1/query-connectivity")
                    .header("Authorization", "Bearer query-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogVersion").isString())
            .andExpect(jsonPath("$.datasets").isArray())
            .andExpect(jsonPath("$.paths").isArray());
}
```

Update `OpenApiContractTest` to expect 35 paths and assert that `/api/v1/query-connectivity` exists.

- [ ] **Step 2: Run the tests and verify RED**

```powershell
$env:JAVA_HOME='E:\env\jdks\ms-21.0.11'
backend\mvnw.cmd -f backend\pom.xml -Dtest=QueryControllerTest,OpenApiContractTest test
```

Expected: endpoint is 404 and the OpenAPI path assertion fails.

- [ ] **Step 3: Add the controller method**

Inject `QueryConnectivityService connectivity` into `QueryController` and add:

```java
@GetMapping("/query-connectivity")
public QueryDtos.QueryConnectivityResponse connectivity() {
    return connectivity.connectivity();
}
```

- [ ] **Step 4: Add the OpenAPI path and schemas**

Add `GET /api/v1/query-connectivity` with a `200` response referencing `QueryConnectivityResponse`. Define schemas matching the Java DTOs exactly:

```yaml
QueryConnectivityResponse:
  type: object
  required: [catalogVersion, datasets, paths]
  properties:
    catalogVersion: { type: string }
    datasets:
      type: array
      items: { $ref: '#/components/schemas/DatasetConnectivity' }
    paths:
      type: array
      items: { $ref: '#/components/schemas/ConnectivityPath' }
ConnectivityPath:
  type: object
  required: [sourceDatasetId, targetDatasetId, status, relationshipIds]
  properties:
    sourceDatasetId: { $ref: '#/components/schemas/Uuid' }
    targetDatasetId: { $ref: '#/components/schemas/Uuid' }
    status: { type: string, enum: [UNIQUE_PATH, AMBIGUOUS_PATH] }
    relationshipIds:
      type: array
      items: { $ref: '#/components/schemas/Uuid' }
```

Also define `DatasetConnectivity` with required `datasetId` and `dataSourceId` UUID properties.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the Task 2 test command again. Expected: all selected backend tests pass.

---

### Task 3: Frontend Connectivity Types and Pure Compatibility Rules

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/features/query/queryCompatibility.ts`
- Create: `frontend/src/features/query/queryCompatibility.test.ts`

**Interfaces:**
- Consumes: `SemanticSearchResult`, selected metric/dimension IDs, and `QueryConnectivityResponse`.
- Produces: the `classifySemanticObjects` and `sortSemanticObjects` pure functions with no React or network dependencies.

- [ ] **Step 1: Write failing pure-function tests**

Cover these cases with fixed datasets and semantic objects:

```typescript
it('allows all metrics and disables dimensions for an empty selection', () => {
  const states = classifySemanticObjects(objects, [], [], connectivity);
  expect(states.metricA.state).toBe('ADDABLE');
  expect(states.dimensionA).toEqual({
    state: 'INCOMPATIBLE',
    reason: 'SELECT_METRIC_FIRST',
  });
});

it('requires every metric dataset to uniquely reach a candidate dimension', () => {
  const states = classifySemanticObjects(objects, ['metricA', 'metricB'], [], connectivity);
  expect(states.dimensionA.state).toBe('ADDABLE');
  expect(states.dimensionMissing.reason).toBe('RELATIONSHIP_PATH_NOT_FOUND');
  expect(states.dimensionAmbiguous.reason).toBe('AMBIGUOUS_RELATIONSHIP_PATH');
});

it('rejects a metric from another data source', () => {
  const states = classifySemanticObjects(objects, ['metricA'], [], connectivity);
  expect(states.metricOtherSource.reason).toBe('DIFFERENT_DATA_SOURCE');
});

it('uses addable selected incompatible ordering while preserving relevance', () => {
  const sorted = sortSemanticObjects(objects, states, true);
  expect(sorted.map(value => value.id)).toEqual([
    'addableHighScore', 'addableLowScore', 'selected', 'incompatible',
  ]);
});
```

Also test retained dimensions constraining the next metric after the last metric is removed.

- [ ] **Step 2: Run the test and verify RED**

```powershell
cd frontend
npm.cmd test -- src/features/query/queryCompatibility.test.ts
```

Expected: module import fails because `queryCompatibility.ts` does not exist.

- [ ] **Step 3: Add the TypeScript API types**

Add:

```typescript
export type ConnectivityPathStatus = 'UNIQUE_PATH' | 'AMBIGUOUS_PATH';
export type DatasetConnectivity = { datasetId: UUID; dataSourceId: UUID };
export type ConnectivityPath = {
  sourceDatasetId: UUID;
  targetDatasetId: UUID;
  status: ConnectivityPathStatus;
  relationshipIds: UUID[];
};
export type QueryConnectivityResponse = {
  catalogVersion: string;
  datasets: DatasetConnectivity[];
  paths: ConnectivityPath[];
};
```

- [ ] **Step 4: Implement the pure compatibility module**

Use these public types and signatures:

```typescript
export type CompatibilityReason =
  | 'SELECT_METRIC_FIRST'
  | 'DIFFERENT_DATA_SOURCE'
  | 'RELATIONSHIP_PATH_NOT_FOUND'
  | 'AMBIGUOUS_RELATIONSHIP_PATH';

export type ObjectCompatibility =
  | { state: 'ADDABLE' }
  | { state: 'SELECTED' }
  | { state: 'INCOMPATIBLE'; reason: CompatibilityReason };

export function classifySemanticObjects(
  objects: SemanticSearchResult[],
  metricIds: string[],
  dimensionIds: string[],
  connectivity: QueryConnectivityResponse,
): Record<string, ObjectCompatibility>;

export function sortSemanticObjects(
  objects: SemanticSearchResult[],
  compatibility: Record<string, ObjectCompatibility>,
  searchActive: boolean,
): SemanticSearchResult[];
```

Build lookup maps once per call. Same-dataset reachability succeeds. An absent directed pair returns `RELATIONSHIP_PATH_NOT_FOUND`; `AMBIGUOUS_PATH` returns `AMBIGUOUS_RELATIONSHIP_PATH`. Candidate metrics must match the selected metric data source and reach every selected dimension. Candidate dimensions must be uniquely reachable from every selected metric.

State ordering is `ADDABLE = 0`, `SELECTED = 1`, `INCOMPATIBLE = 2`. Preserve input order during search; otherwise compare `displayName` with `localeCompare` inside equal states.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the Task 3 test command again. Expected: all compatibility tests pass.

---

### Task 4: Fetch Connectivity Once Per Workbench Visit

**Files:**
- Modify: `frontend/src/api/metricPlatformApi.ts`
- Create: `frontend/src/api/metricPlatformApi.test.ts`
- Modify: `frontend/src/features/query/QueryWorkbenchPage.tsx`

**Interfaces:**
- Consumes: `GET /query-connectivity` and `/semantic-objects:search`.
- Produces: page-owned `connectivity`, `results`, `catalog`, loading, and retry state; no selection-driven requests.

- [ ] **Step 1: Add a failing API test**

```typescript
it('loads the query connectivity snapshot', async () => {
  const payload: QueryConnectivityResponse = {
    catalogVersion: 'catalog-1',
    datasets: [],
    paths: [],
  };
  const fetcher = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(payload), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  );

  await expect(createMetricPlatformApi(fetcher).getQueryConnectivity()).resolves.toEqual(payload);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining('/query-connectivity'),
    expect.objectContaining({
      headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
    }),
  );
});
```

- [ ] **Step 2: Run the API test and verify RED**

```powershell
cd frontend
npm.cmd test -- src/api/metricPlatformApi.test.ts
```

Expected: `getQueryConnectivity` is undefined.

- [ ] **Step 3: Add the API method**

```typescript
getQueryConnectivity: () => request<T.QueryConnectivityResponse>('/query-connectivity'),
```

- [ ] **Step 4: Run the API test and verify GREEN**

Run the Task 4 test command again. Expected: the API test passes.

- [ ] **Step 5: Move page loading ownership into `QueryWorkbenchPage`**

Add a single mount effect for connectivity and retain the debounced semantic search effect. Remove the existing selection-driven validation effect completely:

```typescript
useEffect(() => {
  let cancelled = false;
  void api.getQueryConnectivity().then(
    value => { if (!cancelled) setConnectivity(value); },
    reason => { if (!cancelled) setConnectivityError(errorMessage(reason)); },
  );
  return () => { cancelled = true; };
}, [api]);
```

Do not include `metricIds`, `dimensionIds`, or `request` in this effect. Keep `validateQuery(request)` only inside the explicit execution function.

Expose an optional API dependency for component tests without affecting route usage:

```typescript
type QueryWorkbenchApi = Pick<
  typeof metricPlatformApi,
  'searchSemantic' | 'getQueryConnectivity' | 'validateQuery' | 'executeQuery'
>;

export function QueryWorkbenchPage({ api = metricPlatformApi }: { api?: QueryWorkbenchApi } = {}) {
```

---

### Task 5: Compact Sidebar and Unified Selection Flow

**Files:**
- Modify: `frontend/src/features/query/SemanticObjectPicker.tsx`
- Create: `frontend/src/features/query/SemanticSelectionFlow.tsx`
- Modify: `frontend/src/features/query/SemanticObjectPicker.css`
- Create: `frontend/src/features/query/SemanticObjectPicker.test.tsx`

**Interfaces:**
- Consumes: semantic objects, compatibility map, selected IDs, query text, and callbacks from `QueryWorkbenchPage`.
- Produces: the approved 210-pixel sidebar and an unpartitioned metric-first/dimension-second chip flow.

- [ ] **Step 1: Write the failing presentational test**

Render objects in deliberately mixed order and assert the component presents separate stacked sections with compatibility ordering:

```tsx
it('renders compact stacked libraries with addable objects first', () => {
  render(
    <SemanticObjectPicker
      objects={[disabledMetric, selectedMetric, addableMetric, addableDimension]}
      compatibility={{
        [disabledMetric.id]: { state: 'INCOMPATIBLE', reason: 'DIFFERENT_DATA_SOURCE' },
        [selectedMetric.id]: { state: 'SELECTED' },
        [addableMetric.id]: { state: 'ADDABLE' },
        [addableDimension.id]: { state: 'ADDABLE' },
      }}
      query=""
      loading={false}
      connectivityReady
      metricIds={[selectedMetric.id]}
      dimensionIds={[]}
      onQuery={() => undefined}
      onAdd={() => undefined}
    />,
  );

  const metricLibrary = screen.getByLabelText('指标库');
  expect(within(metricLibrary).getAllByRole('listitem').map(row => row.textContent)).toEqual([
    expect.stringContaining(addableMetric.displayName),
    expect.stringContaining(selectedMetric.displayName),
    expect.stringContaining(disabledMetric.displayName),
  ]);
  expect(
    within(metricLibrary)
      .getByText(disabledMetric.displayName)
      .closest('[role="listitem"]')
      ?.getAttribute('aria-disabled'),
  ).toBe('true');
  expect(screen.queryByText('全部')).toBeNull();
  expect(screen.queryByText('已重算')).toBeNull();
});
```

Add a second test that incompatible rows cannot call `onAdd` through click or drag placement.

- [ ] **Step 2: Run the component test and verify RED**

```powershell
cd frontend
npm.cmd test -- src/features/query/SemanticObjectPicker.test.tsx
```

Expected: the current component API and mixed two-column layout do not satisfy the assertions.

- [ ] **Step 3: Refactor `SemanticObjectPicker` into a sidebar-only component**

Use the props from Step 1. Render one shared search field, followed by `<section aria-label="指标库">` and `<section aria-label="维度库">`. Split by `type`, call `sortSemanticObjects`, and render:

```tsx
<div
  role="listitem"
  aria-disabled={state.state === 'INCOMPATIBLE'}
  draggable={state.state === 'ADDABLE'}
  className={`semantic-object is-${state.state.toLowerCase()}`}
>
  <HolderOutlined className="semantic-drag-handle" aria-hidden="true" />
  <div className="semantic-object-copy">
    <div className="semantic-object-name" title={value.displayName}>
      {value.displayName}
    </div>
    <Typography.Text type="secondary" ellipsis title={incompatibilityText(state)}>
      {state.state === 'INCOMPATIBLE' ? incompatibilityText(state) : value.name}
    </Typography.Text>
  </div>
  <Button
    type="text"
    size="small"
    disabled={state.state !== 'ADDABLE' || !connectivityReady}
    aria-label={`添加${value.displayName}`}
    onClick={() => onAdd(value)}
  >
    {state.state === 'SELECTED' ? '✓' : state.state === 'ADDABLE' ? '+' : '锁'}
  </Button>
</div>
```

Map incompatibility reasons to concise Chinese secondary text. Do not add filter controls.

- [ ] **Step 4: Add `SemanticSelectionFlow`**

Render selected metrics first with pale-green chips, a light separator, then selected dimensions with blue chips. Preserve existing up/down/remove actions and drag reordering, but remove separate bordered metric and dimension drop zones.

- [ ] **Step 5: Implement the compact CSS**

Replace the old `.semantic-builder` layout with:

```css
.query-workbench-grid {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  gap: 10px;
}
.semantic-library {
  align-self: start;
  overflow: hidden;
  border: 1px solid #e4e8ee;
  border-radius: 8px;
  background: #fff;
}
.semantic-object {
  min-height: 26px;
  padding: 3px 4px;
  font-size: 11px;
}
.semantic-object.is-incompatible {
  opacity: 0.58;
  background: #f8f9fa;
  cursor: not-allowed;
}
.semantic-selection.metric { background: #eef9f3; color: #206c48; }
.semantic-selection.dimension { background: #eef7ff; color: #1e65a0; }
```

At widths below 760 pixels, stack the sidebar above the workspace.

- [ ] **Step 6: Run the component test and verify GREEN**

Run the Task 5 test command again. Expected: all picker tests pass.

---

### Task 6: Workbench Integration and Result-Header Actions

**Files:**
- Modify: `frontend/src/features/query/QueryWorkbenchPage.tsx`
- Modify: `frontend/src/features/query/QueryResultTable.tsx`
- Create: `frontend/src/features/query/queryResultCsv.ts`
- Create: `frontend/src/features/query/queryResultCsv.test.ts`
- Create: `frontend/src/features/query/QueryWorkbenchPage.test.tsx`

**Interfaces:**
- Consumes: Task 3 compatibility functions, Task 4 connectivity API, Task 5 sidebar and selection flow.
- Produces: explicit execution, refresh by re-execution, and client-side CSV export from the latest result.

- [ ] **Step 1: Write a failing workbench request-count test**

Use the optional API dependency and a real `MemoryRouter`:

```tsx
it('loads connectivity once and does not validate while selecting objects', async () => {
  const api = fakeApi({ objects, connectivity });
  render(<MemoryRouter><QueryWorkbenchPage api={api} /></MemoryRouter>);

  fireEvent.click(await screen.findByRole('button', { name: `添加${metric.displayName}` }));
  fireEvent.click(await screen.findByRole('button', { name: `添加${dimension.displayName}` }));

  expect(api.getQueryConnectivity).toHaveBeenCalledTimes(1);
  expect(api.validateQuery).not.toHaveBeenCalled();
  expect(api.executeQuery).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: '执行查询' }));
  await waitFor(() => expect(api.validateQuery).toHaveBeenCalledTimes(1));
  expect(api.executeQuery).toHaveBeenCalledTimes(1);
});
```

Also assert `执行查询`, `刷新`, and `导出` are in the `查询结果` card, and that the query-builder card has no button named `执行查询`.

- [ ] **Step 2: Write a failing CSV test**

```typescript
it('escapes CSV headers and values', () => {
  expect(queryResultCsv({
    columns: [
      { name: 'Customer, name', type: 'VARCHAR' },
      { name: 'Note', type: 'VARCHAR' },
    ],
    rows: [['Alice', 'said "hi"'], [null, 'line\nbreak']],
  })).toBe('\uFEFF"Customer, name",Note\r\nAlice,"said ""hi"""\r\n,"line\nbreak"');
});
```

Type the helper input as `Pick<QueryResponse, 'columns' | 'rows'>`.

- [ ] **Step 3: Run the focused tests and verify RED**

```powershell
cd frontend
npm.cmd test -- src/features/query/QueryWorkbenchPage.test.tsx src/features/query/queryResultCsv.test.ts
```

Expected: the integration still validates on selection, the result-header actions are missing, and the CSV helper does not exist.

- [ ] **Step 4: Compose the approved page layout**

In `QueryWorkbenchPage`, calculate compatibility with `useMemo`, render the sidebar and right workspace in `.query-workbench-grid`, and place `SemanticSelectionFlow` above the result card.

Remove the current `useEffect` that calls `validateQuery(request)` after selection changes. Clear stale result and plan locally when selection changes without making a request.

Use the result card `extra` area:

```tsx
<Space size="small">
  <Button aria-label="刷新" disabled={!result || loading} onClick={() => void execute()}>刷新</Button>
  <Button aria-label="导出" disabled={!result || loading} onClick={() => exportResult(result)}>导出</Button>
  <Button
    type="primary"
    aria-label="执行查询"
    disabled={!metricIds.length || loading || !connectivity}
    loading={loading}
    onClick={() => void execute()}
  >
    执行查询
  </Button>
</Space>
```

`execute()` must call `validateQuery(request)` first and call `executeQuery(request)` only when the validation response is valid. If validation returns `RELATIONSHIP_PATH_NOT_FOUND`, `AMBIGUOUS_RELATIONSHIP_PATH`, or `SEMANTIC_OBJECT_NOT_FOUND`, call a shared `reloadCatalogSnapshot()` function once. That function reloads connectivity and increments a `catalogRevision` state included in the semantic-search effect dependencies. Preserve the user's selection and show the validation issue. Do not compare the global connectivity `catalogVersion` with the validation response's selection-specific `catalogVersion`.

- [ ] **Step 5: Implement CSV generation and download**

Implement RFC-style cell escaping in `queryResultCsv` and an `exportQueryResultCsv(result)` browser helper that creates a UTF-8 BOM CSV `Blob`, clicks a temporary download link named `metric-query-<queryId>.csv`, and revokes the object URL.

- [ ] **Step 6: Keep `QueryResultTable` presentation-only**

Continue passing `result` and `loading`; remove any duplicated action controls from the table itself. The result card owns execution, refresh, and export.

- [ ] **Step 7: Run the focused tests and verify GREEN**

Run the Task 6 test command again. Expected: request-count, placement, selection, and CSV tests pass.

---

### Task 7: Full Verification and Live Regression

**Files:**
- Verify all files changed in Tasks 1–6.

**Interfaces:**
- Consumes: complete backend and frontend implementation.
- Produces: evidence that the feature works without changing Data Agent.

- [ ] **Step 1: Run the complete backend test suite**

```powershell
$env:JAVA_HOME='E:\env\jdks\ms-21.0.11'
backend\mvnw.cmd -f backend\pom.xml clean test
```

Expected: BUILD SUCCESS with zero failures and zero errors.

- [ ] **Step 2: Run the complete frontend test suite**

```powershell
cd frontend
npm.cmd test
```

Expected: all Vitest files and tests pass.

- [ ] **Step 3: Run the frontend production build**

```powershell
cd frontend
npm.cmd run build
```

Expected: Prettier, TypeScript, and Vite succeed. The existing bundle-size warning is non-blocking.

- [ ] **Step 4: Verify API request behavior in the browser**

Open `http://localhost:5173/query`, log in as `admin`, and verify:

- the compact metric and dimension libraries are stacked in the left sidebar;
- addable objects appear before selected and incompatible objects;
- incompatible objects are grey and cannot be added or dragged;
- adding and removing objects does not call `/queries:validate` or `/query-connectivity` again;
- the page made one `/query-connectivity` request on entry;
- `执行查询` calls validation and execution;
- refresh re-executes and export downloads the current result as CSV.

- [ ] **Step 5: Verify repository scope and whitespace**

```powershell
git diff --check
git diff --name-only
git -C .. status --short
```

Expected: no whitespace errors; content changes are contained under `metric-platform`; the superproject reports only the Metric Platform submodule as changed by this work.
