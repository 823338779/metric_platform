package com.metricplatform.dbt;

/** 固定发布版本已经开始物理清理，后续查询必须返回 410。 */
public final class DbtReleaseGoneException extends RuntimeException {
    public DbtReleaseGoneException(String message) {
        super(message);
    }
}
