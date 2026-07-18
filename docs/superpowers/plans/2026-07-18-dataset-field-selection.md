# Dataset Field Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make discovered source fields available after the dataset wizard changes steps and provide searchable multi-field add/remove controls.

**Architecture:** Preserve the Ant Design form values that drive metadata loading even while their first-step controls are unmounted. Add a focused controlled `PhysicalFieldSelector` component backed by an Ant Design table; `DatasetEditorPage` continues to own form state and the existing API request shape.

**Tech Stack:** React 19, TypeScript 5.8, Ant Design 6, Vite 7, Playwright browser automation.

## Global Constraints

- Keep `selectedPhysicalFields: string[]` and the existing backend API contract unchanged.
- Selecting a new data source or physical object clears fields selected for the previous object.
- Do not add or modify test files without explicit user authorization.
- Do not create Git commits unless the user explicitly requests them.

---

### Task 1: Preserve metadata-driving form values across wizard steps

**Files:**
- Modify: `frontend/src/features/datasets/DatasetEditorPage.tsx:27-29`

**Interfaces:**
- Consumes: Ant Design `FormInstance` named `form`.
- Produces: stable `dataSourceId: string | undefined` and `sourceObjectKey: string | undefined` values while step-one controls are unmounted.

- [ ] **Step 1: Reproduce the disabled field page without changing test files**

Use a one-off Playwright script piped to Node. Route `listDataSources`, `listSourceObjects`, and `listSourceFields`, select a source and object, click “下一步”, and inspect the second combobox. Expected before the fix: the field control is disabled and the fields route is cleared or not usable after the step change.

- [ ] **Step 2: Preserve watched form fields**

Replace the two watchers with preserved watchers:

```tsx
const dataSourceId = Form.useWatch('dataSourceId', { form, preserve: true }) as
  | string
  | undefined;
const sourceObjectKey = Form.useWatch('sourceObjectKey', { form, preserve: true }) as
  | string
  | undefined;
```

- [ ] **Step 3: Run the browser reproduction again**

Expected after the fix: entering “字段配置” leaves `sourceObjectKey` available, the source fields request completes, and the field selector is enabled.

---

### Task 2: Build the searchable multi-field selector

**Files:**
- Create: `frontend/src/features/datasets/PhysicalFieldSelector.tsx`

**Interfaces:**
- Consumes: `columns: SourceColumn[]`, `loading: boolean`, `value?: string[]`, and `onChange?: (value: string[]) => void`.
- Produces: a controlled selection of physical field names compatible with Ant Design `Form.Item`.

- [ ] **Step 1: Create the controlled component**

Implement a table whose row key is `physicalName`, whose row selection is controlled by `value`, and whose `onChange` emits string keys. Keep `preserveSelectedRowKeys: true` so searching does not discard hidden selections.

```tsx
import { Button, Input, Space, Table, Typography } from 'antd';
import { useMemo, useState } from 'react';
import type { SourceColumn } from '../../api/types';

export function PhysicalFieldSelector({
  columns,
  loading,
  value = [],
  onChange,
}: {
  columns: SourceColumn[];
  loading: boolean;
  value?: string[];
  onChange?: (value: string[]) => void;
}) {
  const [query, setQuery] = useState('');
  const visibleColumns = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return columns;
    return columns.filter(
      (column) =>
        column.physicalName.toLowerCase().includes(needle) ||
        column.physicalType.toLowerCase().includes(needle),
    );
  }, [columns, query]);

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
        <Input.Search
          allowClear
          placeholder="搜索字段名称或类型"
          onChange={(event) => setQuery(event.target.value)}
          style={{ width: 320 }}
        />
        <Space>
          <Typography.Text type="secondary">已选择 {value.length} 个字段</Typography.Text>
          <Button disabled={!value.length} onClick={() => onChange?.([])}>
            清空选择
          </Button>
        </Space>
      </Space>
      <Table<SourceColumn>
        rowKey="physicalName"
        loading={loading}
        pagination={false}
        dataSource={visibleColumns}
        locale={{ emptyText: loading ? '正在加载字段' : '没有可选字段' }}
        rowSelection={{
          selectedRowKeys: value,
          preserveSelectedRowKeys: true,
          onChange: (keys) => onChange?.(keys.map(String)),
        }}
        columns={[
          { title: '物理字段', dataIndex: 'physicalName' },
          { title: '类型', dataIndex: 'physicalType' },
        ]}
      />
    </Space>
  );
}
```

- [ ] **Step 2: Format and type-check the component**

Run: `npm.cmd run format -- frontend/src/features/datasets/PhysicalFieldSelector.tsx`

Expected: Prettier formats the new file without errors.

---

### Task 3: Integrate selection with dataset creation

**Files:**
- Modify: `frontend/src/features/datasets/DatasetEditorPage.tsx:1-13,187-214`

**Interfaces:**
- Consumes: `PhysicalFieldSelector` from Task 2 and the existing `columns` metadata state.
- Produces: `selectedPhysicalFields: string[]` in the existing dataset create request.

- [ ] **Step 1: Import the selector**

```tsx
import { PhysicalFieldSelector } from './PhysicalFieldSelector';
```

- [ ] **Step 2: Replace the multi-select with the controlled field table**

Keep the existing validation rule and replace only its child:

```tsx
<Form form={form} layout="vertical">
  <Form.Item
    name="selectedPhysicalFields"
    label="字段"
    rules={[{ required: true, type: 'array', min: 1, message: '请至少选择一个字段' }]}
  >
    <PhysicalFieldSelector columns={columns} loading={loadingColumns} />
  </Form.Item>
</Form>
```

- [ ] **Step 3: Verify the complete browser behavior without adding a test file**

Run a Playwright script through stdin with mocked API routes. Verify all of the following:

- entering step two shows two mocked fields;
- selecting both rows displays `已选择 2 个字段`;
- deselecting one row displays `已选择 1 个字段`;
- searching hides nonmatching rows without losing selected keys;
- clearing leaves zero selected fields and prevents advancing;
- selecting fields and saving sends the exact final names in `selectedPhysicalFields`.

- [ ] **Step 4: Run fresh project verification**

Run: `npm.cmd test`

Expected: 2 test files and 3 tests pass.

Run: `npm.cmd run build`

Expected: format check, TypeScript build, and Vite production build exit with code 0. The existing bundle-size warning is non-blocking.

- [ ] **Step 5: Review the final diff**

Run: `git diff --check` and `git status --short` from `metric-platform/`.

Expected: no whitespace errors; changes are limited to the approved query-picker fix, dataset field selector, dataset editor integration, and planning documents.

---

### Task 4: Bound create and edit field tables

**Files:**
- Modify: `frontend/src/features/datasets/PhysicalFieldSelector.tsx:24-58`
- Modify: `frontend/src/features/datasets/FieldTable.tsx:1-42`

**Interfaces:**
- Consumes: existing controlled field selections and dataset field updates.
- Produces: tables with fixed headers, an internal vertical viewport, and horizontal overflow protection.

- [ ] **Step 1: Capture the unbounded behavior with a browser measurement**

Render 100 mocked source fields in the create wizard and measure the table body and document heights. Expected before the fix: the document height grows by several thousand pixels and the table body has no bounded scroll viewport.

- [ ] **Step 2: Bound the create selector**

Update the toolbar and table props in `PhysicalFieldSelector`:

```tsx
<Space wrap style={{ width: '100%', justifyContent: 'space-between', rowGap: 8 }}>
  <Input.Search
    allowClear
    placeholder="搜索字段名称或类型"
    onChange={(event) => setQuery(event.target.value)}
    style={{ width: 320, maxWidth: '100%' }}
  />
  {/* existing selection summary and clear button */}
</Space>
```

```tsx
<Table<SourceColumn>
  sticky
  scroll={{ x: 520, y: 400 }}
  // keep the existing row key, loading, selection, columns, and no-pagination props
/>
```

- [ ] **Step 3: Bound the edit table**

Add the equivalent table behavior in `FieldTable`:

```tsx
<Table
  sticky
  scroll={{ x: 720, y: 400 }}
  rowKey="id"
  pagination={false}
  // keep existing data and columns
/>
```

- [ ] **Step 4: Verify both tables manually**

Create mode: 100 fields must render inside a roughly 400-pixel table body with a fixed header. Edit mode: a dataset containing 100 fields must use the same bounded behavior, and display-name inputs and enable switches remain interactive after scrolling.

---

### Task 5: Harden wizard and platform overflow behavior

**Files:**
- Modify: `frontend/src/features/datasets/DatasetEditorPage.tsx:220-250`
- Modify: `frontend/src/layout/PlatformLayout.tsx:51-61`

**Interfaces:**
- Consumes: existing wizard steps and retained Ant Design form state.
- Produces: content that can shrink inside the platform flex layout and preview content that scrolls instead of widening the page.

- [ ] **Step 1: Constrain wizard content and preview**

Read complete preserved form values in preview and constrain long JSON:

```tsx
<pre style={{ maxWidth: '100%', maxHeight: 400, overflow: 'auto', marginBottom: 0 }}>
  {JSON.stringify({ values: form.getFieldsValue(true), filter }, null, 2)}
</pre>
```

Add `minWidth: 0` to the wizard content wrapper:

```tsx
<div style={{ margin: '28px 0', minWidth: 0 }}>{content}</div>
```

- [ ] **Step 2: Let the main content flex item shrink**

Update the nested layout and content:

```tsx
<Layout style={{ minWidth: 0 }}>
  {/* header */}
  <Layout.Content style={{ padding: 24, background: '#f5f7fa', minWidth: 0 }}>
    <Outlet />
  </Layout.Content>
</Layout>
```

- [ ] **Step 3: Verify desktop and narrow layouts**

At 1440×900 and 768×900, assert `document.documentElement.scrollWidth <= window.innerWidth`. Confirm the field toolbar wraps at 768 pixels, the table scrolls internally, and the wizard buttons remain immediately below the bounded content.

---

### Task 6: Final regression verification

**Files:**
- Verify only; do not modify test files.

**Interfaces:**
- Consumes: Tasks 4 and 5.
- Produces: fresh evidence for layout stability and build correctness.

- [ ] **Step 1: Run the 100-field browser scenario**

Verify document height remains close to the viewport rather than growing with all rows, table body `scrollHeight` exceeds `clientHeight`, selection still supports multiple rows, search preserves selection, and the creation request contains the final selected field names.

- [ ] **Step 2: Run project verification**

Run: `npm.cmd test`

Expected: 2 test files and 3 tests pass.

Run: `npm.cmd run build`

Expected: Prettier, TypeScript, and Vite exit with code 0; the existing bundle-size warning remains non-blocking.

- [ ] **Step 3: Review scope**

Run: `git diff --check` and `git status --short` from `metric-platform/`.

Expected: no whitespace errors. Preserve the unrelated user-owned `backend/src/main/java/com/metricplatform/persistence/entity/DataSourceEntity.java` change without editing it.
