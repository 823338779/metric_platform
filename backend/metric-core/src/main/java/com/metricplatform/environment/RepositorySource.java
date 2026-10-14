package com.metricplatform.environment;

import java.util.List;

/** 全局唯一源码仓库，由服务器配置绑定，不接受浏览器指定远端。 */
public interface RepositorySource {
    String repository();

    List<String> branches();

    record Branches(String repository, List<String> branches) {}

    class UnavailableException extends RuntimeException {
        public UnavailableException() {
            super("Git 分支读取失败，请检查服务器仓库配置及访问权限后重试");
        }
    }
}
