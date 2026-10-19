package com.flowdesk;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.flowdesk.common.ListPagination;
import com.flowdesk.mapper.TaskMapper;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 使用真实分页插件生成 SQL，Executor 返回模拟计数；不连接数据库。
class ListPaginationPluginTest {

    @ParameterizedTest
    @ValueSource(strings = { "selectProjectTasks", "selectMyTasks" })
    void pluginRetainsFiltersInCountAndAddsBoundedLimit(String method) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(TaskMapper.class);
        MappedStatement statement = configuration.getMappedStatement(TaskMapper.class.getName() + "." + method);
        var page = ListPagination.page(2, 20);
        var params = new HashMap<String, Object>();
        params.put("page", page);
        params.put("projectId", 7L);
        params.put("scope", "MINE");
        params.put("currentUserId", 9L);
        params.put("userId", 9L);
        params.put("status", "TODO");
        params.put("assigneeId", 13L);
        params.put("unfinishedOnly", true);
        params.put("keyword", "%_literal");
        BoundSql sql = statement.getBoundSql(params);
        Executor executor = mock(Executor.class);
        when(executor.createCacheKey(any(), any(), any(), any())).thenReturn(new CacheKey());
        when(executor.query(any(), same(params), eq(RowBounds.DEFAULT), isNull(), any(), any())).thenAnswer(call -> {
            BoundSql count = call.getArgument(5);
            assertTrue(count.getSql().contains("COUNT(*)"));
            assertTrue(count.getSql().contains("t.deleted_at IS NULL"));
            if (method.equals("selectProjectTasks")) {
                assertTrue(count.getSql().contains("t.project_id = ?"));
                assertTrue(count.getSql().contains("t.assignee_id = ?"));
                assertTrue(count.getSql().contains("'TODO', 'IN_PROGRESS', 'REVIEW'"));
                assertTrue(
                    count
                        .getParameterMappings()
                        .stream()
                        .anyMatch(p -> p.getProperty().equals("assigneeId"))
                );
                assertTrue(
                    count
                        .getParameterMappings()
                        .stream()
                        .anyMatch(p -> p.getProperty().equals("unfinishedOnly"))
                );
            } else {
                assertTrue(count.getSql().contains("JOIN project_member pm"));
                assertTrue(count.getSql().contains("current_account.status = 'ACTIVE'"));
                assertTrue(count.getSql().contains("LOCATE("));
                assertTrue(
                    count
                        .getParameterMappings()
                        .stream()
                        .anyMatch(p -> p.getProperty().equals("keyword"))
                );
            }
            return List.of(73L);
        });
        var plugin = new PaginationInnerInterceptor(DbType.MYSQL);
        assertTrue(plugin.willDoQuery(executor, statement, params, RowBounds.DEFAULT, null, sql));
        assertEquals(73, page.getTotal());
        plugin.beforeQuery(executor, statement, params, RowBounds.DEFAULT, null, sql);
        assertTrue(sql.getSql().contains("LIMIT ?,?"));
        assertEquals(20L, sql.getAdditionalParameter("mybatis_plus_first"));
        assertEquals(20L, sql.getAdditionalParameter("mybatis_plus_second"));
        assertFalse(sql.getSql().contains("%_literal"));
    }
}
