package com.metricplatform.query;

/** 固定发布版本已经开始物理清理，后续查询必须返回 410。 */
public final class ReleaseGoneException extends RuntimeException {
    public ReleaseGoneException(String message) {
        super(message);
    }
}
