package com.metricplatform.catalog;

import com.metricplatform.catalog.BuildContracts.*;

/** 可重建历史投影，游标与所有对象摘要必须原子提交。 */
public interface BuildHistoryRepository {
    void apply(ChangePage page, String expectedCursor);

    String cursor();

    BuildPage list(BuildFilter filter);
}
