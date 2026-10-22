package com.flowdesk.config;

import com.baomidou.mybatisplus.annotation.IEnum;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 仅注册给业务状态：按稳定字符串编码读写，未知编码不能静默变为 null。 */
public final class StatusCodeTypeHandler<E extends Enum<E> & IEnum<String>> extends BaseTypeHandler<E> {

    private final Class<E> type;

    public StatusCodeTypeHandler(Class<E> type) {
        this.type = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, E status, JdbcType jdbcType) throws SQLException {
        statement.setString(index, status.getValue());
    }

    @Override
    public E getNullableResult(ResultSet result, String column) throws SQLException {
        return decode(result.getString(column));
    }

    @Override
    public E getNullableResult(ResultSet result, int column) throws SQLException {
        return decode(result.getString(column));
    }

    @Override
    public E getNullableResult(CallableStatement statement, int column) throws SQLException {
        return decode(statement.getString(column));
    }

    private E decode(String code) throws SQLException {
        if (code == null) {
            return null;
        }
        for (E status : type.getEnumConstants()) {
            if (status.getValue().equals(code)) {
                return status;
            }
        }
        throw new SQLException("Invalid " + type.getSimpleName() + " database code: " + code);
    }
}
