package com.metricplatform.environment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.security.PlatformPrincipal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 校验环境物理目标，环境不拥有源码信息。 */
@Service
public class EnvironmentService {
    private static final Set<String> ADAPTERS = Set.of("starrocks", "postgres", "duckdb");
    private static final String DUCKDB = "duckdb",
            TYPE = "type",
            HOSTS = "hosts",
            PORT = "port",
            PATH = "path";
    private static final String IDENTIFIER = "[a-z][a-z0-9_]{0,62}";
    private static final String DOT_SUFFIX = "\\.+$", EMPTY = "";

    /** 配置持久端口。 */
    private final EnvironmentStore store;

    /** 部署维护的实例地址清单，不含环境配置。 */
    private final JsonNode instances;

    public EnvironmentService(
            EnvironmentStore store, @Value("${EXECUTION_INSTANCES:{}}") String instances) {
        this.store = store;
        try {
            this.instances = new ObjectMapper().readTree(instances);
        } catch (Exception error) {
            throw new IllegalArgumentException("执行实例清单无效", error);
        }
    }

    public EnvironmentPage list() {
        var actor = PlatformPrincipal.current();
        return new EnvironmentPage(store.list(actor.tenantId()));
    }

    public EnvironmentView get(UUID id) {
        var actor = PlatformPrincipal.current();
        return store.get(actor.tenantId(), id);
    }

    public EnvironmentView create(EnvironmentInput input) {
        PlatformPrincipal.current().requireManager();
        validate(input);
        return store.create(PlatformPrincipal.current().tenantId(), input);
    }

    public EnvironmentView update(UUID id, long expected, EnvironmentInput input) {
        PlatformPrincipal.current().requireManager();
        validate(input);
        return store.update(PlatformPrincipal.current().tenantId(), id, expected, input);
    }

    private void validate(EnvironmentInput input) {
        if (input.name() == null
                || input.name().isBlank()
                || input.name().length() > 128
                || input.connection() == null
                || input.credentialRef() == null
                || input.credentialRef().isBlank()
                || input.catalog() == null
                || !input.catalog().matches(IDENTIFIER)
                || input.schema() == null
                || !input.schema().matches(IDENTIFIER)
                || input.threads() < 1
                || input.threads() > 32
                || input.businessTimezone() == null) {
            throw new IllegalArgumentException("环境名称、连接、catalog、schema、凭据引用或并行度无效");
        }
        try {
            ZoneId.of(input.businessTimezone());
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("业务时区必须是有效的 IANA 时区", error);
        }
        var target = input.connection();
        if (target.type() == null
                || !ADAPTERS.contains(target.type())
                || target.instanceId() == null) {
            throw new IllegalArgumentException("执行实例无效");
        }
        JsonNode instance = instances.path(target.instanceId());
        boolean valid = target.type().equals(instance.path(TYPE).asText());
        if (DUCKDB.equals(target.type())) {
            valid &=
                    target.path() != null
                            && Path.of(target.path()).isAbsolute()
                            && Path.of(target.path())
                                    .normalize()
                                    .equals(Path.of(instance.path(PATH).asText()).normalize())
                            && target.host() == null
                            && target.port() == null
                            && input.catalog()
                                    .equals(stem(Path.of(target.path()).getFileName().toString()));
        } else {
            valid &=
                    target.port() != null
                            && target.port() == instance.path(PORT).asInt()
                            && target.host() != null
                            && target.path() == null;
            boolean host = false;
            for (JsonNode alias : instance.path(HOSTS)) {
                host |=
                        target.host() != null
                                && normalizeHost(alias.asText())
                                        .equals(normalizeHost(target.host()));
            }
            valid &= host;
        }
        if (!valid) throw new IllegalArgumentException("EXECUTION_TARGET_UNVERIFIED");
    }

    private static String normalizeHost(String host) {
        return host.toLowerCase(Locale.ROOT).replaceAll(DOT_SUFFIX, EMPTY);
    }

    private static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
