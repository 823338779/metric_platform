package com.metricplatform.source;

import static com.metricplatform.source.DataSourceDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSourceManagementService {
    private final DataSourceMapper sources;
    private final DatasetMapper datasets;
    private final JdbcDialectRegistry dialects;
    private final JdbcConnectionService connections;

    public DataSourceManagementService(
            DataSourceMapper sources,
            DatasetMapper datasets,
            JdbcDialectRegistry dialects,
            JdbcConnectionService connections) {
        this.sources = sources;
        this.datasets = datasets;
        this.dialects = dialects;
        this.connections = connections;
    }

    public List<DataSourceSummary> list(boolean includeDeleted) {
        List<DataSourceEntity> selected =
                includeDeleted
                        ? sources.selectAllIncludingDeleted()
                        : sources.selectList(
                                new LambdaQueryWrapper<DataSourceEntity>()
                                        .orderByAsc(DataSourceEntity::getName));
        return selected.stream().map(this::summary).toList();
    }

    public DataSourceDetail get(UUID id) {
        return detail(entity(id));
    }

    @Transactional
    public DataSourceDetail create(CreateDataSourceRequest request) {
        ensureUniqueName(request.name(), null);
        var dialect = dialects.required(request.databaseType().name());
        if (!dialect.acceptsUrl(request.jdbcUrl()))
            throw new IllegalArgumentException("JDBC URL does not match database type");
        DataSourceEntity source = new DataSourceEntity();
        source.setId(UUID.randomUUID());
        apply(
                source,
                request.name(),
                request.description(),
                request.databaseType(),
                request.jdbcUrl(),
                request.username(),
                request.defaultCatalog(),
                request.defaultSchema());
        source.setDriverClass(dialect.driverClass());
        source.setPassword(request.password());
        source.setStatus(DataSourceStatus.ACTIVE.name());
        source.setHealthStatus(HealthStatus.UNKNOWN.name());
        source.setVersion(0);
        try {
            sources.insert(source);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("data source name already exists");
        }
        return detail(source);
    }

    @Transactional
    public DataSourceDetail update(UUID id, UpdateDataSourceRequest request) {
        DataSourceEntity source = mutable(id);
        if (source.getVersion() != request.version())
            throw new ConflictException("data source was modified by another user");
        ensureUniqueName(request.name(), id);
        var dialect = dialects.required(request.databaseType().name());
        String jdbcUrl =
                request.jdbcUrl() == null || request.jdbcUrl().isBlank()
                        ? source.getJdbcUrl()
                        : request.jdbcUrl();
        if (!dialect.acceptsUrl(jdbcUrl))
            throw new IllegalArgumentException("JDBC URL does not match database type");
        apply(
                source,
                request.name(),
                request.description(),
                request.databaseType(),
                jdbcUrl,
                request.username(),
                request.defaultCatalog(),
                request.defaultSchema());
        source.setDriverClass(dialect.driverClass());
        source.setPassword(request.password());
        if (sources.updateById(source) == 0)
            throw new ConflictException("data source was modified by another user");
        return detail(entity(id));
    }

    public ConnectionTestResponse validateConnection(ConnectionSettingsRequest request) {
        long started = System.nanoTime();
        try (Connection ignored = connections.openReadOnly(settings(request))) {
            return success(started);
        } catch (SQLException | RuntimeException exception) {
            return failure(started, exception);
        }
    }

    @Transactional
    public ConnectionTestResponse test(UUID id) {
        DataSourceEntity source = mutable(id);
        long started = System.nanoTime();
        ConnectionTestResponse response;
        try (Connection ignored = connections.openForTest(source)) {
            response = success(started);
            source.setHealthStatus(HealthStatus.HEALTHY.name());
        } catch (SQLException | RuntimeException exception) {
            response = failure(started, exception);
            source.setHealthStatus(HealthStatus.UNHEALTHY.name());
        }
        source.setLastCheckedAt(LocalDateTime.now());
        source.setLastLatencyMs(response.latencyMs());
        sources.updateById(source);
        return response;
    }

    @Transactional
    public DataSourceDetail activate(UUID id) {
        return changeStatus(id, DataSourceStatus.ACTIVE);
    }

    @Transactional
    public DataSourceDetail deactivate(UUID id) {
        return changeStatus(id, DataSourceStatus.INACTIVE);
    }

    public DataSourceDeletionImpact deletionImpact(UUID id) {
        entity(id);
        List<DependentDataset> impacted =
                datasets
                        .selectList(
                                new LambdaQueryWrapper<DatasetEntity>()
                                        .eq(DatasetEntity::getDataSourceId, id)
                                        .orderByAsc(DatasetEntity::getName))
                        .stream()
                        .map(dataset -> new DependentDataset(dataset.getId(), dataset.getName()))
                        .toList();
        return new DataSourceDeletionImpact(impacted);
    }

    @Transactional
    public void delete(UUID id) {
        DataSourceEntity source = mutable(id);
        source.setStatus(DataSourceStatus.DELETED.name());
        sources.updateById(source);
        sources.softDeleteById(id);
    }

    private DataSourceDetail changeStatus(UUID id, DataSourceStatus status) {
        DataSourceEntity source = mutable(id);
        source.setStatus(status.name());
        sources.updateById(source);
        return detail(entity(id));
    }

    private void apply(
            DataSourceEntity source,
            String name,
            String description,
            DatabaseType databaseType,
            String jdbcUrl,
            String username,
            String defaultCatalog,
            String defaultSchema) {
        source.setName(name.trim());
        source.setDescription(trim(description));
        source.setDatabaseType(databaseType.name());
        source.setJdbcUrl(jdbcUrl.trim());
        source.setUsername(username.trim());
        source.setDefaultCatalog(trim(defaultCatalog));
        source.setDefaultSchema(trim(defaultSchema));
        source.setDatabaseName(trim(defaultCatalog));
    }

    private JdbcConnectionSettings settings(ConnectionSettingsRequest request) {
        return new JdbcConnectionSettings(
                request.databaseType().name(),
                request.jdbcUrl(),
                request.username(),
                request.password(),
                trim(request.defaultCatalog()),
                trim(request.defaultSchema()));
    }

    private DataSourceEntity entity(UUID id) {
        return java.util.Optional.ofNullable(sources.selectById(id))
                .orElseThrow(() -> new NoSuchElementException("data source not found"));
    }

    private DataSourceEntity mutable(UUID id) {
        DataSourceEntity source = entity(id);
        if ("DELETED".equals(source.getStatus()))
            throw new ConflictException("deleted data source cannot be changed");
        return source;
    }

    private void ensureUniqueName(String name, UUID excludedId) {
        boolean exists =
                sources
                        .selectList(
                                new LambdaQueryWrapper<DataSourceEntity>()
                                        .eq(DataSourceEntity::getName, name.trim()))
                        .stream()
                        .anyMatch(
                                source -> excludedId == null || !excludedId.equals(source.getId()));
        if (exists) throw new ConflictException("data source name already exists");
    }

    private DataSourceSummary summary(DataSourceEntity source) {
        return new DataSourceSummary(
                source.getId(),
                source.getName(),
                source.getDescription(),
                type(source),
                first(source.getDefaultCatalog(), source.getDatabaseName()),
                status(source),
                health(source),
                source.getLastCheckedAt(),
                source.getLastLatencyMs());
    }

    private DataSourceDetail detail(DataSourceEntity source) {
        return new DataSourceDetail(
                source.getId(),
                source.getName(),
                source.getDescription(),
                type(source),
                source.getJdbcUrl(),
                source.getUsername(),
                source.getPassword(),
                first(source.getDefaultCatalog(), source.getDatabaseName()),
                source.getDefaultSchema(),
                status(source),
                health(source),
                source.getLastCheckedAt(),
                source.getLastLatencyMs(),
                source.getVersion());
    }

    private static DatabaseType type(DataSourceEntity source) {
        return DatabaseType.valueOf(first(source.getDatabaseType(), "STARROCKS"));
    }

    private static DataSourceStatus status(DataSourceEntity source) {
        return "ENABLED".equals(source.getStatus())
                ? DataSourceStatus.ACTIVE
                : DataSourceStatus.valueOf(source.getStatus());
    }

    private static HealthStatus health(DataSourceEntity source) {
        return HealthStatus.valueOf(first(source.getHealthStatus(), "UNKNOWN"));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static ConnectionTestResponse success(long started) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        return new ConnectionTestResponse(true, ms, "连接成功");
    }

    private static ConnectionTestResponse failure(long started, Exception exception) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        String message =
                exception instanceof SQLException sql
                                && sql.getSQLState() != null
                                && sql.getSQLState().startsWith("28")
                        ? "认证失败"
                        : "连接失败，请检查地址、网络和凭据";
        return new ConnectionTestResponse(false, ms, message);
    }
}
