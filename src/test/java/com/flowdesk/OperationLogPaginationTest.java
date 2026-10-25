package com.flowdesk;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.ListPagination;
import com.flowdesk.controller.ProjectOperationLogController;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.mapper.OperationLogMapper;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.model.Project;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectPermissionService;
import com.flowdesk.vo.OperationLogVO;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OperationLogPaginationTest {
    private OperationLogMapper logs;
    private ProjectMapper projects;
    private ProjectPermissionService permissions;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        logs = mock(OperationLogMapper.class);
        projects = mock(ProjectMapper.class);
        permissions = mock(ProjectPermissionService.class);
        var service = new OperationLogService(logs, new ObjectMapper(), permissions, projects);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectOperationLogController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void defaultRouteReturnsFullTotalWithTwentyRowPage() throws Exception {
        when(projects.selectById(4L)).thenReturn(new Project());
        when(logs.selectProjectLogs(any(), eq(4L))).thenAnswer(call -> {
            Page<OperationLogVO> page = call.getArgument(0);
            assertEquals(1, page.getCurrent());
            assertEquals(20, page.getSize());
            var item = new OperationLogVO();
            item.setId(150L);
            return page.setTotal(150).setRecords(List.of(item));
        });
        mvc.perform(get("/projects/4/operation-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(150))
                .andExpect(jsonPath("$.data.total").value(150))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.pages").value(8));
        var order = inOrder(permissions, projects, logs);
        order.verify(permissions).requireProjectMember(4L);
        order.verify(projects).selectById(4L);
        order.verify(logs).selectProjectLogs(any(), eq(4L));
    }

    @Test
    void sixthPageCanReturnHistoryBeyondTheFormerHundredRowWindow() throws Exception {
        when(projects.selectById(4L)).thenReturn(new Project());
        when(logs.selectProjectLogs(any(), eq(4L))).thenAnswer(call -> {
            Page<OperationLogVO> page = call.getArgument(0);
            assertEquals(6, page.getCurrent());
            assertEquals(100, page.offset());
            var item = new OperationLogVO();
            item.setId(50L);
            return page.setTotal(150).setRecords(List.of(item));
        });
        mvc.perform(get("/projects/4/operation-logs").param("page", "6").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(6))
                .andExpect(jsonPath("$.data.records[0].id").value(50))
                .andExpect(jsonPath("$.data.total").value(150));
    }

    @ParameterizedTest
    @CsvSource({"0,20", "-1,20", "1,0", "1,-1", "1,101", "abc,20", "1,99999999999999999999999"})
    void invalidPaginationIsRejectedWithoutQueryingLogs(String page, String size) throws Exception {
        mvc.perform(get("/projects/4/operation-logs").param("page", page).param("size", size))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(logs, projects, permissions);
    }

    @Test
    void emptyProjectStillHasAValidBoundedPage() throws Exception {
        when(projects.selectById(4L)).thenReturn(new Project());
        when(logs.selectProjectLogs(any(), eq(4L))).thenAnswer(call -> {
            Page<OperationLogVO> page = call.getArgument(0);
            assertEquals(100, page.getSize());
            return page.setTotal(0).setRecords(List.of());
        });
        mvc.perform(get("/projects/4/operation-logs").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.pages").value(0));
    }

    @Test
    void missingProjectCannotQueryLogs() throws Exception {
        mvc.perform(get("/projects/4/operation-logs")).andExpect(status().isNotFound());
        verifyNoInteractions(logs);
    }

    @Test
    void paginationPluginKeepsProjectScopeAndStableOrderingWhenReadingOlderHistory() throws Exception {
        // 执行真实 MyBatis 映射和分页插件；模拟计数，不连接真实数据库。
        var configuration = new MybatisConfiguration();
        configuration.addMapper(OperationLogMapper.class);
        var statement = configuration.getMappedStatement(OperationLogMapper.class.getName() + ".selectProjectLogs");
        var page = ListPagination.<OperationLogVO>page(6, 20);
        var params = new HashMap<String, Object>();
        params.put("page", page);
        params.put("projectId", 4L);
        BoundSql sql = statement.getBoundSql(params);
        assertTrue(sql.getSql().contains("ORDER BY ol.created_at DESC, ol.id DESC"));
        assertFalse(sql.getSql().contains("LIMIT"));
        Executor executor = mock(Executor.class);
        when(executor.createCacheKey(any(), any(), any(), any())).thenReturn(new CacheKey());
        when(executor.query(any(), same(params), eq(RowBounds.DEFAULT), isNull(), any(), any())).thenAnswer(call -> {
            BoundSql count = call.getArgument(5);
            assertTrue(count.getSql().contains("COUNT(*)"));
            assertTrue(count.getSql().contains("ol.project_id = ?"));
            assertTrue(count.getSql().contains("JOIN `user` actor"));
            assertEquals("projectId", count.getParameterMappings().get(0).getProperty());
            return List.of(150L);
        });
        var plugin = new PaginationInnerInterceptor(DbType.MYSQL);
        assertTrue(plugin.willDoQuery(executor, statement, params, RowBounds.DEFAULT, null, sql));
        plugin.beforeQuery(executor, statement, params, RowBounds.DEFAULT, null, sql);
        assertEquals(150, page.getTotal());
        assertTrue(sql.getSql().contains("LIMIT ?,?"));
        assertEquals(100L, sql.getAdditionalParameter("mybatis_plus_first"));
        assertEquals(20L, sql.getAdditionalParameter("mybatis_plus_second"));
    }
}
