package com.metricplatform.catalog;

import com.metricplatform.catalog.BuildContracts.*;
import java.util.List;
import java.util.Map;

/** 可重建历史投影，游标与所有对象摘要必须原子提交。 */
public interface BuildHistoryRepository {
    record ChangeRecord(
            long sequence,
            String repository,
            String objectType,
            String objectId,
            long objectVersion,
            Map<String, Object> summary) {}

    record ChangePage(List<ChangeRecord> items, String nextCursor) {}

    void apply(ChangePage page, String expectedCursor);

    String cursor();

    BuildPage list(BuildFilter filter);
}
