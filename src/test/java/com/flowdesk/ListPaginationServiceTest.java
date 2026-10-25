package com.flowdesk;

import com.flowdesk.enums.ProjectStatus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import com.flowdesk.vo.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListPaginationServiceTest {

    TaskMapper tasks = mock(TaskMapper.class);
    ProjectMapper projects = mock(ProjectMapper.class);
    ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    ProjectTaskSummaryCache cache = mock(ProjectTaskSummaryCache.class);
    NotificationMapper notifications = mock(NotificationMapper.class);
    TaskService taskService = new TaskService(
        tasks,
        projects,
        mock(ProjectMemberMapper.class),
        permissions,
        mock(TaskSubmissionMapper.class),
        mock(TaskCommentMapper.class),
        mock(OperationLogService.class),
        mock(NotificationService.class),
        mock(UserMapper.class),
        cache,
        mock(com.flowdesk.service.TaskReminderService.class)
    );
    NotificationService notificationService = new NotificationService(notifications, mock(UserMapper.class));

    @BeforeEach
    void setup() {
        UserContext.set(new CurrentUser(9L, "USER"));
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "pagination"), Notification.class);
        Project p = new Project();
        p.setId(7L);
        p.setStatus(ProjectStatus.IN_PROGRESS);
        when(projects.selectById(7L)).thenReturn(p);
    }

    @AfterEach
    void cleanup() {
        UserContext.remove();
    }

    @Test
    void projectResultPreservesFilteredTotalAndPageMetadata() {
        TaskVO task = new TaskVO();
        task.setId(42L);
        when(tasks.selectProjectTasks(any(), eq(7L), eq("ALL"), eq(9L), eq("ALL"), isNull(), eq(false))).thenAnswer(call -> {
            Page<TaskVO> page = call.getArgument(0);
            assertEquals(2, page.getCurrent());
            assertEquals(20, page.getSize());
            assertFalse(page.optimizeJoinOfCountSql());
            page.setTotal(73);
            page.setRecords(List.of(task));
            return page;
        });
        var result = taskService.getProjectTasks(7L, "ALL", "ALL", 2, 20, null, false);
        assertEquals(73, result.getTotal());
        assertEquals(4, result.getPages());
        assertEquals(2, result.getPage());
        assertEquals(20, result.getSize());
        assertEquals(42L, result.getRecords().get(0).getId());
    }

    @Test
    void allProjectFiltersArePassedTogetherWithoutOverridingMineScope() {
        when(tasks.selectProjectTasks(any(), eq(7L), eq("MINE"), eq(9L), eq("DONE"), eq(3L), eq(true))).thenAnswer(call ->
            call.<Page<TaskVO>>getArgument(0).setTotal(0).setRecords(List.of())
        );
        var result = taskService.getProjectTasks(7L, " mine ", " done ", 1, 20, 3L, true);
        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        var order = inOrder(permissions, projects, tasks);
        order.verify(permissions).requireProjectMember(7L);
        order.verify(projects).selectById(7L);
        order.verify(tasks).selectProjectTasks(any(), eq(7L), eq("MINE"), eq(9L), eq("DONE"), eq(3L), eq(true));
    }

    @Test
    void permissionIsCheckedBeforeInvalidFiltersOrPagination() {
        doThrow(new BusinessException(403, "无权访问项目")).when(permissions).requireProjectMember(7L);
        assertEquals(
            403,
            assertThrows(BusinessException.class, () -> taskService.getProjectTasks(7L, "BAD", "BAD", 0, 0, -1L, true)).getCode()
        );
        verifyNoInteractions(tasks);
        verify(projects, never()).selectById(anyLong());
    }

    @ParameterizedTest
    @CsvSource({ "0,20", "-1,20", "1,0", "1,-1", "1,101" })
    void invalidPaginationRejectedByAllThreeLists(long page, long size) {
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                taskService.getProjectTasks(7L, "ALL", "ALL", page, size, null, false)
            ).getCode()
        );
        assertEquals(400, assertThrows(BusinessException.class, () -> taskService.getMyTasks(page, size, "ALL", null)).getCode());
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                notificationService.getMyNotifications(page, size, "ALL", "ALL")
            ).getCode()
        );
        verifyNoInteractions(tasks, notifications);
    }

    @ParameterizedTest
    @ValueSource(longs = { 0, -3 })
    void nonPositiveAssigneeRejected(long assignee) {
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                taskService.getProjectTasks(7L, "ALL", "ALL", 1, 20, assignee, true)
            ).getCode()
        );
        verifyNoInteractions(tasks);
    }

    @Test
    void projectAndPersonalTaskFiltersNormalizeIndependentlyOfDefaultLocale() {
        when(tasks.selectProjectTasks(any(), eq(7L), eq("ALL"), eq(9L), eq("IN_PROGRESS"), isNull(), eq(false)))
            .thenAnswer(call -> call.<Page<TaskVO>>getArgument(0).setTotal(0).setRecords(List.of()));
        when(tasks.selectMyTasks(any(), eq(9L), eq("IN_PROGRESS"), eq("")))
            .thenAnswer(call -> call.<Page<TaskVO>>getArgument(0).setTotal(0).setRecords(List.of()));
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            taskService.getProjectTasks(7L, "ALL", " in_progress ", 1, 20, null, false);
            taskService.getMyTasks(1, 20, " in_progress ", null);
        } finally {
            Locale.setDefault(original);
        }
        verify(tasks).selectProjectTasks(any(), eq(7L), eq("ALL"), eq(9L), eq("IN_PROGRESS"), isNull(), eq(false));
        verify(tasks).selectMyTasks(any(), eq(9L), eq("IN_PROGRESS"), eq(""));
    }

    @Test
    void unknownTaskStatusIsRejectedBeforeEitherTaskQuery() {
        for (boolean personal : List.of(true, false)) {
            BusinessException failure = assertThrows(BusinessException.class, () -> {
                if (personal) taskService.getMyTasks(1, 20, "UNKNOWN", null);
                else taskService.getProjectTasks(7L, "ALL", "UNKNOWN", 1, 20, null, false);
            });
            assertEquals(400, failure.getCode());
            assertEquals("任务状态筛选条件不正确", failure.getMessage());
        }
        verifyNoInteractions(tasks);
    }

    @Test
    void myTaskStatusAndLiteralTitleKeywordAreNormalizedBeforePaging() {
        when(tasks.selectMyTasks(any(), eq(9L), eq("IN_PROGRESS"), eq("%_abc\\"))).thenAnswer(call ->
            call.<Page<TaskVO>>getArgument(0).setTotal(22).setRecords(List.of())
        );
        var result = taskService.getMyTasks(1, 20, " in_progress ", "  %_ABC\\  ");
        assertEquals(22, result.getTotal());
        assertEquals(2, result.getPages());
    }

    @Test
    void summaryUsesCurrentUserThreeDayWindowAndNoCacheOrPagedTaskRead() {
        var summary = new MyTaskSummaryVO(80, 20, 10, 5, 40, 35, 75, 7);
        when(tasks.selectMyTaskSummary(eq(9L), any(), any())).thenAnswer(call -> {
            assertEquals(Duration.ofDays(3), Duration.between(call.getArgument(1), call.getArgument(2)));
            return summary;
        });
        assertSame(summary, taskService.getMyTaskSummary());
        verifyNoInteractions(cache);
        verify(tasks, never()).selectMyTasks(any(), anyLong(), anyString(), anyString());
        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));
        assertEquals(MyTaskSummaryVO.empty(), taskService.getMyTaskSummary());
        verify(tasks, times(1)).selectMyTaskSummary(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "ALL",
            "PROJECT_INVITATION",
            "TASK",
            "TASK_REQUEST",
            "TASK_SUBMISSION",
            "PROJECT_ACCEPTANCE",
            "MEMBER_LEAVE_REQUEST",
            "PROJECT_MANAGER_TRANSFER",
            "PROJECT_MEMBER_REMOVED",
            "PROJECT_MEMBER_DISABLED"
        }
    )
    void notificationCategoryAndUnreadScopeAreAppliedToPagedQuery(String category) {
        when(notifications.selectPage(any(), any())).thenAnswer(call -> {
            Page<Notification> page = call.getArgument(0);
            LambdaQueryWrapper<Notification> query = call.getArgument(1);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("recipient_id"));
            assertTrue(sql.contains("read_at IS NULL"));
            assertTrue(sql.contains("ORDER BY created_at DESC,id DESC"));
            assertTrue(query.getParamNameValuePairs().containsValue(9L));
            if (!"ALL".equals(category)) {
                assertTrue(sql.contains("CAST("));
                String expected = "TASK".equals(category) ? "TASK_ASSIGNED" : category;
                if ("MEMBER_LEAVE_REQUEST".equals(category)) expected += "_";
                assertTrue(query.getParamNameValuePairs().containsValue(expected));
            }
            if ("PROJECT_MANAGER_TRANSFER".equals(category)) {
                assertTrue(sql.contains(" OR "));
                assertTrue(query.getParamNameValuePairs().containsValue("PROJECT_MANAGER_TRANSFER_"));
            }
            Notification n = new Notification();
            n.setId(18L);
            page.setRecords(List.of(n));
            page.setTotal(61);
            return page;
        });
        var result = notificationService.getMyNotifications(2, 20, "UNREAD", category);
        assertEquals(61, result.getTotal());
        assertEquals(4, result.getPages());
        assertEquals(18L, result.getRecords().get(0).getId());
    }

    @ParameterizedTest
    @ValueSource(strings = { "READ", "UNREAD", "ALL" })
    void readFilterUsesReadTimestamp(String filter) {
        when(notifications.selectPage(any(), any())).thenAnswer(call -> {
            LambdaQueryWrapper<Notification> query = call.getArgument(1);
            String sql = query.getSqlSegment();
            assertEquals("READ".equals(filter), sql.contains("read_at IS NOT NULL"));
            assertEquals("UNREAD".equals(filter), sql.contains("read_at IS NULL"));
            return call.getArgument(0);
        });
        notificationService.getMyNotifications(1, 20, filter, "ALL");
    }

    @Test
    void invalidFiltersFailBeforeQueryAndGlobalUnreadAndMarkAllIgnorePagination() {
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> notificationService.getMyNotifications(1, 20, "BAD", "ALL")).getCode()
        );
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> notificationService.getMyNotifications(1, 20, "ALL", "BAD")).getCode()
        );
        when(notifications.selectCount(any())).thenAnswer(call -> {
            LambdaQueryWrapper<Notification> query = call.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("read_at IS NULL"));
            assertFalse(sql.contains("type"));
            assertEquals(List.of(9L), List.copyOf(query.getParamNameValuePairs().values()));
            return 57L;
        });
        when(notifications.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<Notification> query = call.getArgument(1);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("read_at IS NULL"));
            assertFalse(sql.contains("type"));
            assertFalse(sql.contains("LIMIT"));
            assertTrue(query.getParamNameValuePairs().containsValue(9L));
            return 57;
        });
        assertEquals(57L, notificationService.getUnreadCount());
        notificationService.markAllAsRead();
        verify(notifications, never()).selectPage(any(), any());
    }
}
