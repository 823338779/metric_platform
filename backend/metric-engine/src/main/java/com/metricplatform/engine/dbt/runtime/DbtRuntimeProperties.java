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
    private boolean enabled;
    private URI baseUri;
    private Duration timeout = Duration.ofSeconds(30);
}
