package com.metricplatform.engine.warehouse;

import com.metricplatform.ontology.WarehouseColumns;
import java.net.SocketTimeoutException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.stereotype.Component;

/** 平台只读取固定内部 catalog 的列目录。SQL 参数不会作为标识符拼接。 */
@Component
public final class StarRocksColumnReader implements WarehouseColumns {
    private static final String SQL =
            "SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.columns WHERE TABLE_SCHEMA = #{schema} AND TABLE_NAME = #{table}";
    private static final String HIDDEN = "METADATA_INVISIBLE",
            TIMEOUT = "METADATA_TIMEOUT",
            DENIED = "METADATA_PERMISSION_DENIED",
            OFFLINE = "METADATA_UNAVAILABLE";

    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String METADATA = "warehouse-metadata";
    private final Map<Access, SqlSessionFactory> factories = new ConcurrentHashMap<>();

    record ColumnType(String name, String type) {}

    interface MetadataMapper {
        @Select(SQL)
        @Options(timeout = 5)
        @ConstructorArgs({
            @Arg(column = "COLUMN_NAME", javaType = String.class),
            @Arg(column = "COLUMN_TYPE", javaType = String.class)
        })
        List<ColumnType> columns(@Param("schema") String schema, @Param("table") String table);
    }

    // 每个受信实例配置一个独立的非连接池会话工厂，元数据账号不参与平台业务事务。
    static SqlSessionFactory factory(Access access) {
        var options = new Properties();
        options.setProperty("user", access.username());
        options.setProperty("password", access.password());
        options.setProperty("connectTimeout", "3000");
        options.setProperty("socketTimeout", "5000");
        var source = new UnpooledDataSource(DRIVER, access.url(), options);
        var config =
                new Configuration(new Environment(METADATA, new JdbcTransactionFactory(), source));
        config.addMapper(MetadataMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

    @Override
    public TableInventory read(TableTarget target, Access access) {
        try (var session =
                factories
                        .computeIfAbsent(access, StarRocksColumnReader::factory)
                        .openSession(true)) {
            var rows =
                    session.getMapper(MetadataMapper.class)
                            .columns(target.schema(), target.table());
            var types = new HashMap<String, String>();
            for (var row : rows) types.put(row.name(), row.type());
            return types.isEmpty()
                    ? TableInventory.unavailable(HIDDEN)
                    : new TableInventory(
                            Collections.unmodifiableMap(types),
                            access.complete(),
                            Instant.now().toString(),
                            null);
        } catch (PersistenceException ex) {
            String reason = OFFLINE;
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLTimeoutException || cause instanceof SocketTimeoutException)
                    return TableInventory.unavailable(TIMEOUT);
                if (cause instanceof SQLException sql
                        && (Set.of(1044, 1045, 1142, 1143).contains(sql.getErrorCode())
                                || "28000".equals(sql.getSQLState()))) reason = DENIED;
            }
            return TableInventory.unavailable(reason);
        }
    }
}
