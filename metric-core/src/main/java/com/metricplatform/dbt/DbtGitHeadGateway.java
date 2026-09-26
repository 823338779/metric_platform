package com.metricplatform.dbt;

/** 发布用例读取受控 main 快照的端口。 */
public interface DbtGitHeadGateway {
    DbtProjectRevision readDbtRevision(String projectSubdir);
}
