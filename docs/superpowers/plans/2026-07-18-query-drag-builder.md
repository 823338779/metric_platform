# Query Drag Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the query workbench metric and dimension selects with a responsive drag-and-drop semantic query builder.

**Architecture:** `SemanticObjectPicker` remains the controlled boundary used by `QueryWorkbenchPage`, but renders a searchable object library and two typed drop zones. Native browser drag events provide mouse interaction, while explicit add, move, and remove buttons provide keyboard and touch support; the existing API and query request remain unchanged.

**Tech Stack:** React 19, TypeScript 5.8, Ant Design 6, native HTML Drag and Drop API, Vite 7, Playwright browser automation.

## Global Constraints

- Keep the existing `metricIds: string[]`, `dimensionIds: string[]`, `onMetrics`, and `onDimensions` interface.
- Do not change `QueryRequest` or backend endpoints.
- Do not add a drag-and-drop dependency.
- Do not add or modify test files without explicit user authorization.
- Do not create Git commits unless the user explicitly requests them.
- Preserve unrelated user-owned backend changes.

---

### Task 1: Implement deterministic selection placement

**Files:**
- Create: `frontend/src/features/query/semanticSelection.ts`

**Interfaces:**
- Produces: `placeSelection(values: string[], id: string, index?: number): string[]` and `removeSelection(values: string[], id: string): string[]`.
- Consumes: plain semantic object ID arrays.

- [ ] **Step 1: Add pure immutable helpers**

```ts
export function placeSelection(values: string[], id: string, index = values.length) {
  const next = values.filter((value) => value !== id);
  next.splice(Math.max(0, Math.min(index, next.length)), 0, id);
  return next;
}

export function removeSelection(values: string[], id: string) {
  return values.filter((value) => value !== id);
}
```

`placeSelection` handles both duplicate-free addition and reordering by removing the existing ID before inserting it at the requested index.

- [ ] **Step 2: Run TypeScript through the project build after integration**

No test file is added because project instructions prohibit modifying test code. Browser interaction in Task 4 verifies these helpers through the real component.

---

### Task 2: Build the drag-and-drop picker

**Files:**
- Replace: `frontend/src/features/query/SemanticObjectPicker.tsx`
- Create: `frontend/src/features/query/SemanticObjectPicker.css`

**Interfaces:**
- Consumes: the unchanged picker props and `metricPlatformApi.searchSemantic(query)`.
- Produces: a controlled semantic object library plus typed metric and dimension drop zones.

- [ ] **Step 1: Define drag payload and safe parsing**

```ts
type SemanticType = 'METRIC' | 'DIMENSION';
type DragPayload = { id: string; type: SemanticType };
const dragMime = 'application/x-metric-platform-semantic';

function readDragPayload(event: React.DragEvent): DragPayload | undefined {
  try {
    const value = JSON.parse(event.dataTransfer.getData(dragMime)) as DragPayload;
    return value.id && (value.type === 'METRIC' || value.type === 'DIMENSION')
      ? value
      : undefined;
  } catch {
    return undefined;
  }
}
```

- [ ] **Step 2: Load search results with stale-response protection**

Use `query`, `results`, `catalog`, `loading`, `error`, and a monotonically increasing `requestId` ref. Run an initial empty search, debounce typed searches by 200 ms, merge every successful result into `catalog`, and ignore responses whose request ID is not current.

```tsx
useEffect(() => {
  const current = ++requestId.current;
  const timer = window.setTimeout(() => {
    setLoading(true);
    setError('');
    void metricPlatformApi
      .searchSemantic(query)
      .then((values) => {
        if (current !== requestId.current) return;
        setResults(values.filter((value) => value.type !== 'DATASET'));
        setCatalog((previous) => ({
          ...previous,
          ...Object.fromEntries(values.map((value) => [value.id, value])),
        }));
      })
      .catch((reason) => {
        if (current === requestId.current)
          setError(reason instanceof Error ? reason.message : '语义对象加载失败');
      })
      .finally(() => {
        if (current === requestId.current) setLoading(false);
      });
  }, 200);
  return () => window.clearTimeout(timer);
}, [query]);
```

- [ ] **Step 3: Render the object library**

Render one searchable, internally scrolling list. Each item shows type, display name, code, selected state, a native draggable handle, and an explicit “添加” button. Clicking add routes metrics to `onMetrics(placeSelection(metricIds, id))` and dimensions to `onDimensions(placeSelection(dimensionIds, id))`.

- [ ] **Step 4: Render typed drop zones**

Create a local `SemanticDropZone` component receiving `type`, `title`, `ids`, `catalog`, and `onChange`. A compatible drop calls `placeSelection(ids, payload.id, targetIndex)`; incompatible drops leave state unchanged and show `message.warning('指标和维度需要放入对应区域')`.

Each selected item must include:

- `draggable` and a visible drag handle;
- an item-level drop target for reordering;
- “上移”“下移”和“移除” buttons with disabled boundaries;
- the object display name and code from `catalog`, falling back to its ID.

- [ ] **Step 5: Add responsive and bounded CSS**

Use a two-column grid with `minmax(240px, 2fr) minmax(320px, 3fr)`, switching to one column below 900 px. Bound the library and selected-zone lists with internal scrolling, use full-width controls, and prevent long names from widening the page.

---

### Task 3: Preserve query workbench behavior

**Files:**
- Verify: `frontend/src/features/query/QueryWorkbenchPage.tsx`

**Interfaces:**
- Consumes: unchanged selection arrays from Task 2.
- Produces: unchanged `QueryRequest` validation and execution.

- [ ] **Step 1: Confirm no caller changes are required**

`QueryWorkbenchPage` must continue to pass:

```tsx
<SemanticObjectPicker
  metricIds={metricIds}
  dimensionIds={dimensionIds}
  onMetrics={setMetrics}
  onDimensions={setDimensions}
/>
```

- [ ] **Step 2: Confirm the request shape remains unchanged**

```ts
{
  metricIds,
  dimensions: dimensionIds.map((dimensionId) => ({ dimensionId })),
  relationshipIds: [],
}
```

---

### Task 4: Browser behavior verification

**Files:**
- Verify only; do not modify test files.

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: direct evidence for mouse, keyboard/touch fallback, search, layout, and query request behavior.

- [ ] **Step 1: Verify primary interaction**

With mocked semantic results, use Playwright drag events to add two metrics and one dimension. Verify selected names appear in the correct zones, duplicate drops do not duplicate IDs, wrong-type drops do not alter state, and dragging an existing selected object changes its order.

- [ ] **Step 2: Verify accessible fallback**

Use the visible add buttons, then the up, down, and remove buttons. Verify boundary buttons are disabled and the selected arrays update identically to drag operations.

- [ ] **Step 3: Verify search and errors**

Return two searches out of order and confirm only the latest response is rendered. Reject a search and confirm existing selected items remain visible while the library shows the error.

- [ ] **Step 4: Verify responsive bounds**

At 1440×900 and 768×900 with 100 objects, assert no document horizontal overflow, no additional document-height overflow, the object library scrolls internally, and the layout changes from two columns to one.

- [ ] **Step 5: Verify query validation**

Mock `/queries:validate`, add selections, and assert the body retains ordered `metricIds`, mapped `dimensions`, and an empty `relationshipIds` array.

---

### Task 5: Final project verification

**Files:**
- Verify only.

**Interfaces:**
- Consumes: completed drag builder.
- Produces: fresh build and scope evidence.

- [ ] **Step 1: Format changed files**

Run:

```powershell
npx.cmd prettier --write --ignore-path ../.prettierignore src/features/query/SemanticObjectPicker.tsx src/features/query/SemanticObjectPicker.css src/features/query/semanticSelection.ts
```

- [ ] **Step 2: Run existing tests**

Run: `npm.cmd test`

Expected: 2 test files and 3 tests pass.

- [ ] **Step 3: Run production build**

Run: `npm.cmd run build`

Expected: Prettier, TypeScript, and Vite exit with code 0; the existing bundle-size warning remains non-blocking.

- [ ] **Step 4: Review scope**

Run: `git diff --check` and `git status --short` from `metric-platform/`.

Expected: no whitespace errors. Preserve `backend/src/main/java/com/metricplatform/persistence/entity/DataSourceEntity.java` without editing it.

