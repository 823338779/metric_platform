package com.metricplatform.publication;

/** 固定的 main 提交与其受支持的项目输入树摘要。 */
public record ProjectRevision(String sha, String projectDigest) {}
