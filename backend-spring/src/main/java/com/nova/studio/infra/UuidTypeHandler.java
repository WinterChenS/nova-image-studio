package com.nova.studio.infra;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * WIN-16 (ADR-11) — MyBatis ships no built-in TypeHandler for
 * {@link UUID} (the previous JdbcTemplate path relied on Spring's conversion).
 * Registered globally for the UUID columns ({@code users.id}, {@code models.id},
 * {@code models.user_id}, {@code settings.user_id}, {@code tasks.user_id}).
 * Binding delegates to the JDBC driver ({@code setObject}), exactly like the
 * JdbcTemplate code did.
 */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getObject(columnName, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getObject(columnIndex, UUID.class);
    }
}
