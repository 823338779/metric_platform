package com.metricplatform.dbt;

/** 固定的 main 提交与其受支持 dbt 输入树摘要。 */
public record DbtProjectRevision(String sha, String projectDigest) {}
