package com.metricplatform.persistence;

import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(UUID.class)
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID value, JdbcType type)
            throws SQLException {
        ps.setObject(i, value);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : UUID.fromString(value.toString());
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : UUID.fromString(value.toString());
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int column) throws SQLException {
        Object value = cs.getObject(column);
        return value == null ? null : UUID.fromString(value.toString());
    }
}
