package com.metricplatform.publication;

/** 发布用例读取受控 main 快照的端口。 */
public interface GitHeadGateway {
    ProjectRevision readRevision(String projectSubdir);
}
