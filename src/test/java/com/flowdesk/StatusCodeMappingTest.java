package com.flowdesk;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.flowdesk.config.MybatisPlusConfig;
import com.flowdesk.enums.*;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 实际 MyBatis 配置、参数处理器及结果处理器；JDBC 对象为替身，不连接数据库。
class StatusCodeMappingTest {

    record Mapping(
        Class<?> entity,
        Class<? extends Enum<?>> status,
        Class<?> mapper,
        String property,
        String column,
        String select
    ) {}

    static Stream<Mapping> mappings() {
        return Stream.of(
            new Mapping(Project.class, ProjectStatus.class, ProjectMapper.class, "status", "status", "selectByIdForUpdate"),
            new Mapping(Task.class, TaskStatus.class, TaskMapper.class, "status", "status", "selectByIdForUpdate"),
            new Mapping(
                ProjectAcceptance.class,
                ProjectAcceptanceStatus.class,
                ProjectAcceptanceMapper.class,
                "reviewStatus",
                "review_status",
                "selectById"
            ),
            new Mapping(
                TaskSubmission.class,
                TaskSubmissionStatus.class,
                TaskSubmissionMapper.class,
                "reviewStatus",
                "review_status",
                "selectPendingSubmission"
            ),
            new Mapping(
                ProjectInvitation.class,
                ProjectInvitationStatus.class,
                ProjectInvitationMapper.class,
                "status",
                "status",
                "selectById"
            ),
            new Mapping(TaskRequest.class, TaskRequestStatus.class, TaskRequestMapper.class, "status", "status", "selectById"),
            new Mapping(
                MemberLeaveRequest.class,
                MemberLeaveRequestStatus.class,
                MemberLeaveRequestMapper.class,
                "status",
                "status",
                "selectById"
            ),
            new Mapping(
                ProjectManagerTransfer.class,
                ProjectManagerTransferStatus.class,
                ProjectManagerTransferMapper.class,
                "status",
                "status",
                "selectById"
            ),
            new Mapping(User.class, UserStatus.class, UserMapper.class, "status", "status", "selectByIdForUpdate"),
            new Mapping(
                ProjectMember.class,
                ProjectMemberStatus.class,
                ProjectMemberMapper.class,
                "status",
                "status",
                "selectMemberForUpdate"
            )
        );
    }

    static MybatisConfiguration configuration() {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        new MybatisPlusConfig().statusTypeHandlers().customize(configuration);
        return configuration;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private TypeHandler<Object> handler(MybatisConfiguration configuration, Mapping mapping) {
        return (TypeHandler) configuration.getTypeHandlerRegistry().getTypeHandler(mapping.status());
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void allCodesUseActualRegisteredHandlerWithStrictInvalidAndNullReads(Mapping mapping) throws Exception {
        var handler = handler(configuration(), mapping);
        assertInstanceOf(EnumTypeHandler.class, handler);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        CallableStatement callable = mock(CallableStatement.class);
        for (Enum<?> status : mapping.status().getEnumConstants()) {
            String code = (String) ((IEnum<?>) status).getValue();
            assertEquals(code, status.name(), "持久化状态的枚举名称必须与原有编码一致");
            handler.setParameter(statement, 1, status, null);
            verify(statement).setString(1, code);
            handler.setParameter(statement, 2, status, JdbcType.VARCHAR);
            verify(statement).setObject(2, code, Types.VARCHAR);
            when(result.getString(mapping.column())).thenReturn(code);
            when(result.getString(1)).thenReturn(code);
            when(callable.getString(1)).thenReturn(code);
            assertSame(status, handler.getResult(result, mapping.column()));
            assertSame(status, handler.getResult(result, 1));
            assertSame(status, handler.getResult(callable, 1));
        }
        handler.setParameter(statement, 1, null, JdbcType.VARCHAR);
        verify(statement).setNull(1, Types.VARCHAR);
        when(result.getString(mapping.column())).thenReturn(null);
        when(result.getString(1)).thenReturn(null);
        when(callable.getString(1)).thenReturn(null);
        assertNull(handler.getResult(result, mapping.column()));
        assertNull(handler.getResult(result, 1));
        assertNull(handler.getResult(callable, 1));
        for (String invalid : List.of("UNKNOWN", "", " pending ", "active")) {
            when(result.getString(mapping.column())).thenReturn(invalid);
            when(result.getString(1)).thenReturn(invalid);
            when(callable.getString(1)).thenReturn(invalid);
            var error = assertThrows(ResultMapException.class, () -> handler.getResult(result, mapping.column()));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertThrows(ResultMapException.class, () -> handler.getResult(result, 1));
            assertThrows(ResultMapException.class, () -> handler.getResult(callable, 1));
        }
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void baseMapperInsertAndUpdateBindOriginalStringCode(Mapping mapping) throws Exception {
        var configuration = configuration();
        configuration.addMapper(mapping.mapper());
        Object entity = mapping.entity().getConstructor().newInstance();
        mapping.entity().getMethod("setId", Long.class).invoke(entity, 7L);
        Object status = mapping.status().getEnumConstants()[0];
        mapping
            .entity()
            .getMethod("set" + capitalize(mapping.property()), mapping.status())
            .invoke(entity, status);
        var statement = configuration.getMappedStatement(mapping.mapper().getName() + ".insert");
        BoundSql sql = statement.getBoundSql(entity);
        var parameter = sql
            .getParameterMappings()
            .stream()
            .filter(p -> p.getProperty().equals(mapping.property()))
            .findFirst()
            .orElseThrow();
        assertInstanceOf(EnumTypeHandler.class, parameter.getTypeHandler());
        PreparedStatement jdbc = mock(PreparedStatement.class);
        configuration.newParameterHandler(statement, entity, sql).setParameters(jdbc);
        int index = sql.getParameterMappings().indexOf(parameter) + 1;
        verify(jdbc).setString(index, (String) ((IEnum<?>) status).getValue());

        var update = configuration.getMappedStatement(mapping.mapper().getName() + ".updateById");
        var updateParams = Map.of(Constants.ENTITY, entity);
        var updateSql = update.getBoundSql(updateParams);
        var updateParameter = updateSql
            .getParameterMappings()
            .stream()
            .filter(p -> p.getProperty().equals(Constants.ENTITY + "." + mapping.property()))
            .findFirst()
            .orElseThrow();
        // updateById 的 et 来自 Map，构建阶段类型为 Object；实际绑定时再按枚举类型查注册表。
        assertInstanceOf(UnknownTypeHandler.class, updateParameter.getTypeHandler());
        PreparedStatement updateJdbc = mock(PreparedStatement.class);
        configuration.newParameterHandler(update, updateParams, updateSql).setParameters(updateJdbc);
        verify(updateJdbc).setString(
            updateSql.getParameterMappings().indexOf(updateParameter) + 1,
            (String) ((IEnum<?>) status).getValue()
        );
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void baseAndHandwrittenEntitySelectsReadCodesThroughRegisteredHandler(Mapping mapping) throws Exception {
        var configuration = configuration();
        configuration.addMapper(mapping.mapper());
        for (String select : List.of("selectById", mapping.select())) {
            var statement = configuration.getMappedStatement(mapping.mapper().getName() + "." + select);
            for (Enum<?> status : mapping.status().getEnumConstants()) {
                Object entity = readOne(configuration, statement, mapping.column(), (String) ((IEnum<?>) status).getValue());
                assertSame(
                    status,
                    mapping
                        .entity()
                        .getMethod("get" + capitalize(mapping.property()))
                        .invoke(entity)
                );
            }
            assertThrows(ResultMapException.class, () -> readOne(configuration, statement, mapping.column(), "INVALID"));
        }
    }

    private Object readOne(MybatisConfiguration configuration, MappedStatement statement, String column, String code)
        throws Exception {
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn(column);
        when(metadata.getColumnName(1)).thenReturn(column);
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnClassName(1)).thenReturn(String.class.getName());
        when(result.next()).thenReturn(true, false);
        when(result.getString(column)).thenReturn(code);
        Statement jdbc = mock(Statement.class);
        when(jdbc.getResultSet()).thenReturn(result);
        var handler = new DefaultResultSetHandler(
            mock(Executor.class),
            statement,
            mock(ParameterHandler.class),
            null,
            null,
            RowBounds.DEFAULT
        );
        return handler.handleResultSets(jdbc).get(0);
    }

    @Test
    void enumValuesInLambdaWrapperAndConditionalUpdateAreBoundAsCodes() throws Exception {
        var configuration = configuration();
        configuration.addMapper(TaskMapper.class);
        var wrapper = new LambdaQueryWrapper<Task>()
            .eq(Task::getProjectId, 5L)
            .in(Task::getStatus, TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.REVIEW)
            .isNull(Task::getDeletedAt);
        var params = new HashMap<String, Object>();
        params.put(Constants.WRAPPER, wrapper);
        params.put(Constants.ENTITY, null);
        var select = configuration.getMappedStatement(TaskMapper.class.getName() + ".selectList");
        var sql = select.getBoundSql(params);
        PreparedStatement jdbc = mock(PreparedStatement.class);
        configuration.newParameterHandler(select, params, sql).setParameters(jdbc);
        verify(jdbc).setString(2, "TODO");
        verify(jdbc).setString(3, "IN_PROGRESS");
        verify(jdbc).setString(4, "REVIEW");

        var update = configuration.getMappedStatement(TaskMapper.class.getName() + ".transitionStatusIfCurrent");
        var updateParams = new HashMap<String, Object>(
            Map.of("taskId", 7L, "expectedStatus", TaskStatus.REVIEW, "newStatus", TaskStatus.DONE)
        );
        updateParams.put("completedAt", null);
        updateParams.put("updatedAt", null);
        PreparedStatement updateJdbc = mock(PreparedStatement.class);
        configuration.newParameterHandler(update, updateParams, update.getBoundSql(updateParams)).setParameters(updateJdbc);
        verify(updateJdbc).setString(1, "DONE");
        verify(updateJdbc).setString(5, "REVIEW");
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
