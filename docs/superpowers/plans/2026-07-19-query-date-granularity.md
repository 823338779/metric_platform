# Query Date Granularity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an inline granularity selector for time dimensions and submit the smallest supported granularity by default.

**Architecture:** `QueryWorkbenchPage` will own complete `DimensionSelection[]` values so validation and execution cannot discard granularity. `SemanticObjectPicker` will keep its existing semantic catalog, resolve a time dimension once when it is added, cache its allowed granularities, and render a selector in the selected-dimension row.

**Tech Stack:** React 19, TypeScript 5.8, Ant Design 6, existing Metric Platform semantic API client.

## Global Constraints

- Only address missing time granularity; do not change relationship compatibility behavior.
- Order granularities as `DAY`, `WEEK`, `MONTH`, `QUARTER`, `YEAR` and default to the first supported value.
- Do not modify backend code, API contracts, dependencies, or test-related files.
- Preserve unrelated worktree changes and do not create commits.
- Add concise comments for the new defaulting and semantic-resolution behavior.

---

### Task 1: Preserve dimension selections in the query request

**Files:**
- Modify: `frontend/src/features/query/QueryWorkbenchPage.tsx:1-55`

**Interfaces:**
- Consumes: existing `DimensionSelection` and `QueryRequest` types from `frontend/src/api/types.ts`.
- Produces: `dimensions: DimensionSelection[]` and `onDimensions(value: DimensionSelection[])` props for `SemanticObjectPicker`.

- [ ] **Step 1: Record the existing failure with a focused type check**

Run: `npm.cmd --prefix frontend run build`

Expected: PASS before the change. This establishes the existing frontend baseline; project instructions prohibit adding a regression test.

- [ ] **Step 2: Store complete dimension selections**

Replace the ID-only dimension state and request mapping with:

```tsx
import type {
  DimensionSelection,
  QueryRequest,
  QueryResponse,
  ValidationResponse,
} from '../../api/types';

const [dimensions, setDimensions] = useState<DimensionSelection[]>([]);
const request = useMemo<QueryRequest>(
  () => ({ metricIds, dimensions, relationshipIds: [] }),
  [metricIds, dimensions],
);
```

Pass the state through to the picker:

```tsx
<SemanticObjectPicker
  metricIds={metricIds}
  dimensions={dimensions}
  onMetrics={setMetrics}
  onDimensions={setDimensions}
/>
```

- [ ] **Step 3: Run the TypeScript build to expose the picker contract mismatch**

Run: `npm.cmd --prefix frontend exec tsc -b`

Expected: FAIL because `SemanticObjectPicker` still declares `dimensionIds` instead of `dimensions`.

---

### Task 2: Resolve time dimensions and render the granularity selector

**Files:**
- Modify: `frontend/src/features/query/SemanticObjectPicker.tsx:1-250`

**Interfaces:**
- Consumes: `dimensions: DimensionSelection[]`, `onDimensions(value: DimensionSelection[])`, `metricPlatformApi.resolveSemantic`, and `SemanticSearchResult.summary.dimensionType`.
- Produces: requests containing the chosen `DimensionSelection.granularity` and an inline Ant Design `Select` for time dimensions.

- [ ] **Step 1: Update imports, props, and fixed granularity ordering**

Import `Select`, `DimensionSelection`, and `TimeGranularity`, then add the documented fixed order and helper:

```tsx
const granularityOrder: TimeGranularity[] = ['DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR'];

function smallestAllowedGranularity(values: TimeGranularity[]) {
  // Do not trust persistence order; query semantics define the resolution order explicitly.
  return granularityOrder.find((granularity) => values.includes(granularity));
}
```

Change the component contract to:

```tsx
dimensions: DimensionSelection[];
onDimensions: (value: DimensionSelection[]) => void;
```

Derive `dimensionIds` with `dimensions.map(({ dimensionId }) => dimensionId)`.

- [ ] **Step 2: Resolve and default newly added time dimensions**

Add local cached options:

```tsx
const [granularities, setGranularities] = useState<Record<string, TimeGranularity[]>>({});
```

Implement addition at a requested position:

```tsx
const addDimension = async (id: string, index = dimensions.length) => {
  const semantic = catalog[id];
  let allowed: TimeGranularity[] = [];
  let granularity: TimeGranularity | undefined;
  if (semantic?.summary.dimensionType === 'TIME') {
    // Resolve only selected time dimensions because search results intentionally contain summaries.
    const resolved = await metricPlatformApi.resolveSemantic({
      objects: [{ type: 'DIMENSION', id }],
      relationshipIds: [],
    });
    allowed = resolved.objects[0]?.allowedGranularities ?? [];
    granularity = smallestAllowedGranularity(allowed);
    setGranularities((current) => ({ ...current, [id]: allowed }));
  }
  const next = dimensions.filter((value) => value.dimensionId !== id);
  next.splice(Math.max(0, Math.min(index, next.length)), 0, { dimensionId: id, granularity });
  onDimensions(next);
};
```

Route click and first-time drop additions through `addDimension`. Keep existing selections on the synchronous reorder path so dragging an already selected dimension does not resolve it again.

- [ ] **Step 3: Preserve selection values during reorder and removal**

Translate reordered IDs back to existing selections:

```tsx
const reorderDimensions = (ids: string[]) =>
  onDimensions(
    ids.flatMap((id) => {
      const selection = dimensions.find((value) => value.dimensionId === id);
      return selection ? [selection] : [];
    }),
  );
```

When removing a dimension, filter it from `dimensions`; do not alter metric placement behavior.

- [ ] **Step 4: Add an extension point to selected rows**

Add optional `onAdd` and `renderExtra` props to `SemanticDropZone`. In `place`, call `onAdd(payload.id, index)` only when the ID is not already selected; otherwise retain `placeSelection` reordering. Render `renderExtra?.(id)` beside the existing row actions.

- [ ] **Step 5: Render time-dimension granularity controls**

For the dimension drop zone, provide a renderer equivalent to:

```tsx
renderExtra={(id) => {
  const semantic = catalog[id];
  if (semantic?.summary.dimensionType !== 'TIME') return null;
  const selection = dimensions.find((value) => value.dimensionId === id);
  return (
    <Select<TimeGranularity>
      aria-label={`${semantic.displayName}日期粒度`}
      value={selection?.granularity}
      options={(granularities[id] ?? []).map((value) => ({ value, label: value }))}
      onChange={(granularity) =>
        onDimensions(
          dimensions.map((value) =>
            value.dimensionId === id ? { ...value, granularity } : value,
          ),
        )
      }
    />
  );
}}
```

Catch resolution failures through the picker's existing error state and do not add the dimension until metadata resolution succeeds.

- [ ] **Step 6: Format the changed frontend files**

Run:

```powershell
npm.cmd --prefix frontend exec prettier -- --write src/features/query/QueryWorkbenchPage.tsx src/features/query/SemanticObjectPicker.tsx
```

Expected: both files are formatted without touching unrelated files.

- [ ] **Step 7: Verify type checking now passes**

Run: `npm.cmd --prefix frontend exec tsc -b`

Expected: PASS with exit code 0.

---

### Task 3: Verify unchanged behavior and the generated request

**Files:**
- Verify only; do not modify test files.

**Interfaces:**
- Consumes: completed frontend implementation.
- Produces: build and runtime evidence for default and user-selected granularity behavior.

- [ ] **Step 1: Run existing frontend tests**

Run: `npm.cmd --prefix frontend test`

Expected: all existing Vitest tests pass with zero failures.

- [ ] **Step 2: Run the complete frontend build**

Run: `npm.cmd --prefix frontend run build`

Expected: Prettier check, TypeScript build, and Vite production build all pass.

- [ ] **Step 3: Inspect the local browser request**

Open `/query`, select the known `order_date` time dimension, and inspect the request to `/api/v1/queries:validate`.

Expected request fragment:

```json
{
  "dimensions": [
    {
      "dimensionId": "e6a6bcdd-ce92-4aed-b813-a02fae08b2eb",
      "granularity": "DAY"
    }
  ]
}
```

Change the selector to `MONTH` and confirm the next validation request contains `"granularity":"MONTH"`. Confirm a standard dimension remains `{ "dimensionId": "..." }` without a granularity.

- [ ] **Step 4: Review the final scope**

Run: `git diff -- frontend/src/features/query/QueryWorkbenchPage.tsx frontend/src/features/query/SemanticObjectPicker.tsx`

Expected: only dimension-selection state, time-dimension resolution, and the inline granularity control changed; there are no relationship compatibility changes and no test-file changes.
