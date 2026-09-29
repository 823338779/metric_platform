package com.metricplatform.publication;

/** 读取固定提交的项目源码；引擎适配器负责项目格式与路径校验。 */
public interface ProjectSourceReader extends GitHeadGateway {
    String readYamlAtSha(String projectSubdir, String sha, String sourcePath);
}
