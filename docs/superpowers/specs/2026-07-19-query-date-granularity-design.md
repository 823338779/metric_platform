# Query Date Granularity Design

## Goal

Add date-granularity selection to the Metric Platform query workbench so every selected time dimension is submitted with a valid granularity. When a time dimension is first selected, use its smallest allowed granularity by default.

This change addresses only missing time granularity. It does not change semantic relationship compatibility or filter dimensions by dataset relationships.

## User Experience

- Selected standard dimensions keep their current appearance and behavior.
- Selected time dimensions display an inline granularity selector.
- Granularities are ordered from smallest to largest as `DAY`, `WEEK`, `MONTH`, `QUARTER`, and `YEAR`.
- The initial selection is the first supported value in that fixed order.
- Users can change the selected granularity before validation or execution.
- Removing and re-adding a time dimension initializes it to the smallest supported granularity again.

## Frontend Data Flow

The query workbench will store dimension selections as `DimensionSelection[]` instead of storing only dimension IDs. The semantic picker will continue to use IDs for placement and ordering, while also retaining the selected semantic object metadata needed to identify time dimensions and their supported granularities.

When a dimension is added:

1. Read its dimension metadata from the semantic search result.
2. If it is a time dimension, choose the smallest supported granularity using the fixed order.
3. Add `{ dimensionId, granularity }` to the workbench selection.
4. If it is a standard dimension, add `{ dimensionId }`.

When the user changes a granularity, update only that dimension selection. The validation and execution requests use the same selection array.

## Component Changes

- `QueryWorkbenchPage` owns `DimensionSelection[]` and builds `QueryRequest` without dropping granularity.
- `SemanticObjectPicker` receives dimension selections and exposes selection updates. It renders an Ant Design selector only for selected time dimensions.
- Semantic search result typing exposes the dimension metadata required by the picker, using the existing semantic search response rather than adding a new API call.
- A small pure helper determines the smallest supported granularity and keeps the ordering rule separate from rendering.

## Boundaries and Error Handling

- A time dimension with no supported granularity remains selectable but has no fabricated value; backend validation continues to report the invalid semantic definition.
- Existing API and backend behavior remain unchanged.
- Relationship-path errors remain visible through the existing validation response and are outside this change.
- No test files are added or modified, following the workspace instruction. Verification uses the existing frontend checks plus a browser-level request inspection against the running local application.

## Success Criteria

- Selecting a time dimension automatically assigns its smallest allowed granularity.
- The selected time dimension shows a granularity control and allows changing the value.
- Validation and execution requests contain the currently selected granularity.
- Standard dimensions are still submitted without a granularity.
- Existing frontend formatting, type checking, build, and unchanged tests pass.
