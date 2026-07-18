# Query Connectivity-Aware Object Picker Design

## Goal

Prevent users from adding metric or dimension combinations that the Metric Platform query planner cannot execute, without sending a validation request after every selection change. Redesign the query workbench around a compact left object sidebar and a focused query/result workspace.

This change applies only to Metric Platform.

## Query Compatibility Semantics

Compatibility is not an undirected connected-component check. The frontend must follow the existing planner semantics:

- All selected metrics must belong to the same data source.
- A dimension is compatible only when every selected metric dataset can reach the dimension dataset through one unique, directed, non-expanding relationship path.
- Objects in the same dataset are directly compatible.
- A missing path is incompatible.
- Multiple valid paths are ambiguous and therefore incompatible in this version because the workbench does not expose relationship-path selection.
- A candidate metric must use the same data source as the selected metrics and must be able to reach every already-selected dimension dataset.
- A candidate dimension must be reachable from every selected metric dataset.
- When no metric is selected and no dimensions remain selected, all metrics are available. Dimensions are disabled until a metric provides the query anchor.
- Removing the last metric does not silently remove selected dimensions. In that state, candidate metrics are evaluated against the retained dimensions, while additional dimensions remain disabled.

These rules prevent both `RELATIONSHIP_PATH_NOT_FOUND` and `AMBIGUOUS_RELATIONSHIP_PATH` from being introduced through the picker.

## Connectivity API

Add one authenticated endpoint:

```http
GET /api/v1/query-connectivity
```

Response:

```json
{
  "catalogVersion": "catalog-123",
  "datasets": [
    {
      "datasetId": "uuid",
      "dataSourceId": "uuid"
    }
  ],
  "paths": [
    {
      "sourceDatasetId": "uuid",
      "targetDatasetId": "uuid",
      "status": "UNIQUE_PATH",
      "relationshipIds": ["uuid"]
    },
    {
      "sourceDatasetId": "uuid",
      "targetDatasetId": "uuid",
      "status": "AMBIGUOUS_PATH",
      "relationshipIds": []
    }
  ]
}
```

Rules:

- `paths` contains directed reachability results computed with the same active relationships, cardinality restrictions, and path-resolution implementation used by `QueryPlanner`.
- `UNIQUE_PATH` means the pair can participate in a query and includes the resolved relationship IDs.
- `AMBIGUOUS_PATH` means the pair is disabled by this workbench version.
- A pair absent from `paths` has no usable path.
- A dataset is always reachable from itself; same-dataset entries need not be repeated.
- `datasets` supplies the data-source mapping required for metric-to-metric compatibility.
- The response is scoped to active semantic resources and carries the current `catalogVersion`.

The backend owns graph interpretation. The frontend does not reproduce relationship traversal or cardinality logic.

## Loading and Refresh Behavior

When the query workbench opens, it loads the semantic object catalog and connectivity response in parallel. The connectivity response is stored for the lifetime of the page.

Adding, removing, or reordering a metric or dimension causes no network request. The frontend recomputes candidate states synchronously from:

- selected metric and dimension IDs;
- each semantic object's existing `datasetId`;
- dataset-to-data-source mappings;
- the directed connectivity matrix.

Connectivity is fetched again only when:

- the user reloads or re-enters the query workbench;
- final validation reports `RELATIONSHIP_PATH_NOT_FOUND`, `AMBIGUOUS_RELATIONSHIP_PATH`, or `SEMANTIC_OBJECT_NOT_FOUND`, indicating that the page snapshot may be stale; or
- the connectivity request itself is manually retried after failure.

The existing `/queries:validate` call remains before execution as the final consistency and stale-catalog guard. It is not called for picker changes.

## Frontend Compatibility Calculation

For each semantic object, calculate one state:

- `ADDABLE`: not selected and adding it preserves all query invariants;
- `SELECTED`: already part of the query;
- `INCOMPATIBLE`: adding it would violate data-source, reachability, or path-uniqueness rules.

The frontend returns a stable reason code for incompatible objects:

- `SELECT_METRIC_FIRST`
- `DIFFERENT_DATA_SOURCE`
- `RELATIONSHIP_PATH_NOT_FOUND`
- `AMBIGUOUS_RELATIONSHIP_PATH`

The reason appears as concise secondary text or a tooltip on the disabled row. Internal calculation functions are pure and independent from React so the rules can be tested directly.

## Library Ordering

Metric and dimension sections are sorted independently after each local selection change:

1. `ADDABLE`
2. `SELECTED`
3. `INCOMPATIBLE`

Within the same state, retain semantic-search relevance order when a search is active; otherwise use display name. This prevents arbitrary row movement while keeping actionable objects at the top.

The interface does not expose “全部”, “可添加”, or “不可添加” filters.

## Page Layout

### Left Object Sidebar

- A narrow sidebar of approximately 210 pixels.
- One shared search input for both object types.
- Metrics and dimensions appear as compact stacked sections in one outer container.
- Object rows use small typography and approximately 26-pixel height.
- Metrics use a pale green semantic color; dimensions use blue.
- Addable rows have an add control, selected rows show a check, and incompatible rows are grey and disabled.
- The sidebar contains no recalculation status, “可查询”, “已重算”, or compatibility-filter controls.

### Query Builder

- The right-side `构建指标查询` card contains only the selected-object flow.
- Metrics appear first as pale-green chips; dimensions follow as blue chips.
- Metrics and dimensions are not enclosed in separate bordered panels.
- Removing or reordering chips updates the sidebar locally.

### Query Result

- `执行查询` is located in the `查询结果` header.
- It appears beside `刷新` and `导出`, with `执行查询` as the primary action.
- The query builder contains no result-limit, sort, or filter controls.

## Loading and Error States

- While the initial connectivity request is loading, object rows remain visible but add actions are temporarily disabled. Do not display “已重算”.
- If connectivity loading fails, disable all add actions and show one retryable error at the top of the object sidebar. Do not guess compatibility.
- If semantic search fails, preserve selected chips and show the existing catalog-loading error.
- If final validation returns a path or missing-object issue because the catalog changed, refresh connectivity and catalog once, then present the validation issue without automatically changing the user's selection. Do not compare the connectivity snapshot's global `catalogVersion` with the query validation response's selection-specific `catalogVersion`.

## Testing

### Backend

- Verify directed unique paths are returned as `UNIQUE_PATH` with relationship IDs.
- Verify reverse-only, missing, and expanding paths are not reported as usable.
- Verify multiple paths are returned as `AMBIGUOUS_PATH`.
- Verify same-dataset reachability is implicit.
- Verify inactive/deleted datasets and relationships are excluded.
- Verify the response contains dataset-to-data-source mappings and `catalogVersion`.

### Frontend Logic

- Verify the first metric can be selected without connectivity restrictions when the selection is empty.
- Verify retained dimensions constrain candidate metrics after the last selected metric is removed.
- Verify dimensions are incompatible before a metric anchor exists.
- Verify candidate metrics require the same data source and preserve compatibility with selected dimensions.
- Verify candidate dimensions must be uniquely reachable from every selected metric dataset.
- Verify missing and ambiguous paths are disabled.
- Verify local selection changes do not call the connectivity or validation APIs.
- Verify state ordering is `ADDABLE`, `SELECTED`, then `INCOMPATIBLE`.

### Frontend UI

- Verify metric and dimension sections are stacked in the narrow left sidebar.
- Verify incompatible rows are disabled and cannot be clicked or dragged.
- Verify selected metrics are green and selected dimensions are blue in one unpartitioned flow.
- Verify no compatibility filter or recalculation wording is rendered.
- Verify `执行查询`, `刷新`, and `导出` are in the result header.
- Verify the complete frontend test suite and production build.

## Out of Scope

- Letting users choose among ambiguous relationship paths.
- Changing the planner's directed, non-expanding relationship semantics.
- Removing final server-side query validation.
- Adding query limit, sorting, or filtering controls to this page.
- Changes to Data Agent.
