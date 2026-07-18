# Query Time Granularity Design

## Goal

Allow users to choose a supported date granularity for each selected time dimension in the Metric Platform query workbench. A newly selected time dimension automatically uses its configured default granularity.

## Scope

- Change only the Metric Platform frontend query experience.
- Do not change backend validation or semantic API contracts.
- Do not add, delete, or modify test-related code, per workspace instructions.
- Preserve the existing metric and dimension selection workflow.

## User Experience

- A standard dimension behaves exactly as it does today.
- A selected time dimension displays a granularity selector in its selected-dimension card.
- The selector only offers values from the dimension's `allowedGranularities`.
- Labels are localized as 日, 周, 月, 季度, 年.
- The initial value is `defaultGranularity` when it is allowed; otherwise it is the first allowed granularity.
- Removing a time dimension clears its granularity selection.
- Re-adding it initializes the selection again from the current dimension definition.

## Data Flow

1. `QueryWorkbenchPage` receives selected dimension IDs from `SemanticObjectPicker`.
2. It loads missing dimension definitions through a new `metricPlatformApi.getDimension(id)` method.
3. It stores dimension definitions and selected granularities by dimension ID.
4. It passes time-dimension metadata, selected values, and change callbacks to the picker.
5. Query requests include `{ dimensionId, granularity }` for time dimensions and `{ dimensionId }` for standard dimensions.
6. Automatic validation and execution remain disabled while selected dimension definitions are loading or a time dimension lacks a valid granularity.

## Component Changes

### API client

Add `getDimension(id)` using `GET /dimensions/{id}` and the existing `Dimension` type.

### Query workbench

Maintain dimension-definition and granularity state, initialize default values, remove stale values, and build a complete `QueryRequest`.

### Semantic object picker

Extend the selected-dimension rendering path with optional time-granularity metadata and an Ant Design `Select`. The selector must not interfere with drag-and-drop or removal controls.

## Error Handling

- Show the existing query-page error alert when dimension metadata cannot be loaded.
- Do not issue validation requests until all selected dimensions are ready.
- Keep the execute button disabled when request construction is incomplete.
- If a dimension has no allowed time granularity, surface a clear frontend error instead of sending an invalid request.

## Verification

- Run the existing Metric Platform frontend test command without modifying test files.
- Run TypeScript/build verification.
- Use the local query page to select `order_date`, confirm the default `DAY` value, change it to another allowed value, and inspect the validation request payload.
- Confirm the validation API returns `valid: true` for the generated request.
