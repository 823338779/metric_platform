package com.metricplatform.gitsync;

/** Webhook 只通知当前选中的 Git 消费模式重新读取固定远端。 */
public interface GitRefreshTrigger {
    void requestRefresh();
}
