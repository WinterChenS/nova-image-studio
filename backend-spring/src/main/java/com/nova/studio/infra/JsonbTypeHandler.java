package com.nova.studio.infra;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * WIN-16 (ADR-11) — binds a String-held JSON value to a PostgreSQL
 * {@code jsonb} column. MyBatis-Plus' generated INSERT/UPDATE would otherwise
 * send it as {@code character varying}, which PostgreSQL rejects for jsonb
 * columns; binding with {@link Types#OTHER} lets the driver send an
 * "unknown"-typed parameter that PostgreSQL coerces to the column type
 * (equivalent to the previous {@code ?::jsonb} casts). Reads use
 * {@code getString} (jsonb text form, same as JdbcTemplate).
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
