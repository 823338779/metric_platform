package com.metricplatform.persistence.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/** 引擎原生定义以 JSONB 原样往返，不在持久化层解释字段。 */
public final class JsonbTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, String value, JdbcType type)
            throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(value);
        statement.setObject(index, json);
    }

    @Override
    public String getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getString(column);
    }

    @Override
    public String getNullableResult(ResultSet result, int index) throws SQLException {
        return result.getString(index);
    }

    @Override
    public String getNullableResult(CallableStatement statement, int index) throws SQLException {
        return statement.getString(index);
    }
}
