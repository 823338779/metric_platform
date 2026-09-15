package com.metricplatform.gitsync;

/** 协议拒绝结果；路径指向文档节点，不回显输入内容或数据库凭据。 */
public final class ProtocolException extends IllegalArgumentException {
    private final String path;

    public ProtocolException(String path, String message) {
        super(message);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
