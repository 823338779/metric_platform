package com.metricplatform.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.environment.EnvironmentContracts.BuildExecutionSnapshot;
import com.metricplatform.ontology.WarehouseColumns.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 每个实例单独授权租户与额外 schema；配置由平台部署注入。 */
@ConfigurationProperties("metric-platform.ontology.warehouse")
public record WarehouseMetadataPolicy(List<Instance> instances) {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String DENIED = "TARGET_NOT_ALLOWED";
    static final String UNRESOLVED = "TARGET_UNRESOLVED";
    static final String NO_CREDENTIALS = "METADATA_CREDENTIALS_MISSING";
    static final String INTERNAL = "default_catalog";
    static final String STARROCKS = "starrocks";
    static final String DATABASE = "database", SCHEMA = "schema", ALIAS = "alias";

    public record Instance(
            String id,
            boolean enabled,
            String url,
            String username,
            String password,
            String generation,
            boolean complete,
            Map<String, List<String>> tenants)
            implements Access {
        @Override
        public String toString() {
            return Objects.toString(id);
        }
    }

    record Resolution(TableTarget target, Access access, String reason) {}

    public WarehouseMetadataPolicy {
        instances = instances == null ? List.of() : List.copyOf(instances);
        if (instances.stream().map(Instance::id).distinct().count() != instances.size())
            throw new IllegalArgumentException("元数据实例配置重复");
    }

    boolean active() {
        return instances.stream().anyMatch(Instance::enabled);
    }

    // 对访问策略和目标的非秘密字段计算稳定标识。部署方轮换凭据时递增 generation。
    String version() {
        if (!active()) return "off";
        var parts = new TreeMap<String, List<?>>();
        for (var i : instances) {
            var grants = new TreeMap<String, List<String>>();
            if (i.tenants() != null)
                i.tenants()
                        .forEach(
                                (key, schemas) ->
                                        grants.put(key, schemas.stream().sorted().toList()));
            parts.put(
                    i.id(),
                    Arrays.asList(
                            i.id(),
                            i.enabled(),
                            i.url(),
                            i.username(),
                            i.generation(),
                            i.complete(),
                            i.password() != null && !i.password().isBlank(),
                            grants));
        }

        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(JSON.writeValueAsBytes(parts)));
        } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    Resolution resolve(
            String tenant,
            UUID environment,
            BuildExecutionSnapshot snapshot,
            Map<String, ?> resource) {
        if (snapshot == null
                || !environment.equals(snapshot.environmentId())
                || snapshot.connection() == null) return new Resolution(null, null, UNRESOLVED);
        var connection = snapshot.connection();
        var instance =
                instances.stream()
                        .filter(i -> i.id().equals(connection.instanceId()))
                        .findFirst()
                        .orElse(null);
        if (!STARROCKS.equals(connection.type()) || instance == null || !instance.enabled())
            return new Resolution(null, null, null);
        var database = resource.get(DATABASE);
        if (snapshot.catalog() == null || database != null && !snapshot.catalog().equals(database))
            return new Resolution(null, null, UNRESOLVED);
        if (!INTERNAL.equals(snapshot.catalog())) return new Resolution(null, null, null);
        if (instance.tenants() == null || !instance.tenants().containsKey(tenant))
            return new Resolution(null, null, DENIED);
        if (!(resource.get(SCHEMA) instanceof String schema)
                || schema.isBlank()
                || !(resource.get(ALIAS) instanceof String alias)
                || alias.isBlank()) return new Resolution(null, null, UNRESOLVED);
        if (!schema.equals(snapshot.schema()) && !instance.tenants().get(tenant).contains(schema))
            return new Resolution(null, null, DENIED);
        if (instance.url() == null
                || instance.username() == null
                || instance.username().isBlank()
                || instance.password() == null
                || instance.password().isBlank()) return new Resolution(null, null, NO_CREDENTIALS);
        return new Resolution(
                new TableTarget(instance.id(), INTERNAL, schema, alias), instance, null);
    }
}
