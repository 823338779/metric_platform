package com.metricplatform.engine.dbt.runtime;

import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 固定的外部服务地址；公开 API 与 Git 内容都不能改写。 */
@Component
@ConfigurationProperties("metric-platform.dbt-runtime")
@Getter
@Setter
public class DbtRuntimeProperties {
    /** 运行服务全部请求使用的部署凭据。 */
    private String serviceToken;

    /** 唯一构建执行服务的固定部署地址。 */
    private URI baseUri = URI.create("http://127.0.0.1:8000");

    /** 单次服务请求的最长等待时间。 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 同步选项和查询的等待上限，覆盖服务执行时限与结果传输。 */
    private Duration queryTimeout = Duration.ofSeconds(1860);
}
