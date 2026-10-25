package com.flowdesk;

import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.UserStatus;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.enums.TaskStatus;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.*;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskHandoverTest {

    TaskMapper tasks = mock(TaskMapper.class);
    ProjectMapper projects = mock(ProjectMapper.class);
    ProjectMemberMapper members = mock(ProjectMemberMapper.class);
    ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    TaskSubmissionMapper submissions = mock(TaskSubmissionMapper.class);
    TaskCommentMapper comments = mock(TaskCommentMapper.class);
    UserMapper users = mock(UserMapper.class);
    OperationLogService logs = mock(OperationLogService.class);
    NotificationService notifications = mock(NotificationService.class);
    ProjectTaskSummaryCache cache = mock(ProjectTaskSummaryCache.class);
    TaskService service = new TaskService(
        tasks,
        projects,
        members,
        permissions,
        submissions,
        comments,
        logs,
        notifications,
        users,
        cache
    );
    HandoverTaskDTO dto = new HandoverTaskDTO(2L, 3L, "  账号禁用，交接开发  ");

    @BeforeEach
    void setup() {
        UserContext.set(new CurrentUser(1L, "USER"));
        when(tasks.selectById(10L)).thenReturn(task("IN_PROGRESS", 2L));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project("IN_PROGRESS"));
        when(tasks.selectByIdForUpdate(10L)).thenReturn(task("IN_PROGRESS", 2L));
        when(members.selectMemberForUpdate(4L, 3L)).thenReturn(member("ACTIVE", "DEVELOPER"));
        when(users.selectByIdForUpdate(2L)).thenReturn(user("DISABLED", "USER"));
        when(users.selectByIdForUpdate(3L)).thenReturn(user("ACTIVE", "USER"));
        when(tasks.handoverDisabledAssigneeIfCurrent(eq(10L), eq(4L), eq(2L), eq(3L), any())).thenReturn(1);
    }

    @AfterEach
    void cleanup() {
        UserContext.remove();
    }

    Task task(String status, Long assignee) {
        Task t = new Task();
        t.setId(10L);
        t.setProjectId(4L);
        t.setTitle("测试任务");
        t.setStatus(TaskStatus.fromCode(status));
        t.setAssigneeId(assignee);
        return t;
    }

    Project project(String status) {
        Project p = new Project();
        p.setId(4L);
        p.setStatus(ProjectStatus.fromCode(status));
        return p;
    }

    User user(String status, String role) {
        User u = new User();
        u.setStatus(UserStatus.fromCode(status));
        u.setSystemRole(role);
        return u;
    }

    ProjectMember member(String status, String role) {
        ProjectMember m = new ProjectMember();
        m.setStatus(ProjectMemberStatus.fromCode(status));
        m.setRole(role);
        return m;
    }

    @Test
    void successfulHandoverRecordsHistoryNotifiesAndInvalidatesAfterCommit() {
        service.handoverDisabledAssignee(10L, dto);
        var before = ArgumentCaptor.forClass(Object.class);
        var after = ArgumentCaptor.forClass(Object.class);
        verify(logs).record(
            eq(4L),
            eq("TASK"),
            eq(10L),
            eq("HANDOVER_TASK"),
            anyString(),
            before.capture(),
            after.capture()
        );
        assertEquals(Map.of("assigneeId", 2L, "status", "IN_PROGRESS"), before.getValue());
        Map<?, ?> result = (Map<?, ?>) after.getValue();
        assertEquals(3L, result.get("assigneeId"));
        assertEquals("TODO", result.get("status"));
        assertEquals("账号禁用，交接开发", result.get("reason"));
        assertNotNull(result.get("handedOverAt"));
        var notice = ArgumentCaptor.forClass(CreateNotificationDTO.class);
        verify(notifications).createNotification(notice.capture());
        assertEquals(3L, notice.getValue().getRecipientId());
        assertEquals(1L, notice.getValue().getActorId());
        assertEquals(10L, notice.getValue().getTargetId());
        verify(cache).evictAfterCommit(4L);
        verifyNoInteractions(comments, submissions);
        var order = inOrder(projects, permissions, tasks, members, users);
        order.verify(permissions).requireProjectManager(4L);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(permissions).requireProjectManagerForUpdate(4L);
        order.verify(tasks).selectByIdForUpdate(10L);
        order.verify(members).selectMemberForUpdate(4L, 3L);
        order.verify(users).selectByIdForUpdate(2L);
        order.verify(users).selectByIdForUpdate(3L);
    }

    @Test
    void preparingProjectAndManagerAsNewTaskAssigneeAreAllowed() {
        when(projects.selectByIdForUpdate(4L)).thenReturn(project("PREPARING"));
        when(members.selectMemberForUpdate(4L, 3L)).thenReturn(member("ACTIVE", "PROJECT_MANAGER"));
        service.handoverDisabledAssignee(10L, dto);
        verify(cache).evictAfterCommit(4L);
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_ACCEPTANCE", "COMPLETED", "ARCHIVED", "CANCELLED" })
    void frozenProjectsRejectHandover(String status) {
        when(projects.selectByIdForUpdate(4L)).thenReturn(project(status));
        rejected(409);
        verify(tasks, never()).selectByIdForUpdate(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = { "TODO", "REVIEW", "DONE", "CANCELLED" })
    void onlyInProgressTaskMayBeHandedOver(String status) {
        when(tasks.selectByIdForUpdate(10L)).thenReturn(task(status, 2L));
        rejected(409);
    }

    @Test
    void changedAssigneeOrMissingTaskAfterWaitingIsRejected() {
        when(tasks.selectByIdForUpdate(10L)).thenReturn(task("IN_PROGRESS", 9L));
        rejected(409);
        when(tasks.selectByIdForUpdate(10L)).thenReturn(null);
        rejected(409);
    }

    @Test
    void enabledOriginalAccountCannotBeHandedOver() {
        when(users.selectByIdForUpdate(2L)).thenReturn(user("ACTIVE", "USER"));
        rejected(409);
    }

    @Test
    void targetMustStillBeAnEffectiveProjectMember() {
        when(members.selectMemberForUpdate(4L, 3L)).thenReturn(null);
        rejected(409);
        when(members.selectMemberForUpdate(4L, 3L)).thenReturn(member("INACTIVE", "DEVELOPER"));
        rejected(409);
        when(members.selectMemberForUpdate(4L, 3L)).thenReturn(member("ACTIVE", "DEVELOPER"));
        when(users.selectByIdForUpdate(3L)).thenReturn(user("DISABLED", "USER"));
        rejected(409);
        when(users.selectByIdForUpdate(3L)).thenReturn(user("ACTIVE", "SYSTEM_ADMIN"));
        rejected(409);
    }

    @Test
    void permissionIsCheckedBeforeAndAfterProjectLock() {
        doThrow(new BusinessException(403, "非本项目负责人")).when(permissions).requireProjectManager(4L);
        rejected(403);
        verify(tasks, never()).selectByIdForUpdate(anyLong());
        doReturn(null).when(permissions).requireProjectManager(4L);
        doThrow(new BusinessException(403, "项目负责人已变化")).when(permissions).requireProjectManagerForUpdate(4L);
        rejected(403);
    }

    @Test
    void missingReasonSameAssigneeAndStaleWriteAreRejectedWithoutSideEffects() {
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                service.handoverDisabledAssignee(10L, new HandoverTaskDTO(2L, 3L, "  "))
            ).getCode()
        );
        assertEquals(
            409,
            assertThrows(BusinessException.class, () ->
                service.handoverDisabledAssignee(10L, new HandoverTaskDTO(2L, 2L, "交接"))
            ).getCode()
        );
        when(tasks.handoverDisabledAssigneeIfCurrent(eq(10L), eq(4L), eq(2L), eq(3L), any())).thenReturn(0);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.handoverDisabledAssignee(10L, dto)).getCode());
        verifyNoInteractions(logs, notifications, cache);
    }

    @Test
    void duplicateConcurrentHandoverHasOnlyOneWinnerInConditionalWriteSimulation() throws Exception {
        var barrier = new CyclicBarrier(2);
        var won = new AtomicBoolean();
        when(tasks.handoverDisabledAssigneeIfCurrent(eq(10L), eq(4L), eq(2L), eq(3L), any())).thenAnswer(call -> {
            barrier.await(5, TimeUnit.SECONDS);
            return won.compareAndSet(false, true) ? 1 : 0;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> attempt = () -> {
            UserContext.set(new CurrentUser(1L, "USER"));
            try {
                service.handoverDisabledAssignee(10L, dto);
                return 200;
            } catch (BusinessException e) {
                return e.getCode();
            } finally {
                UserContext.remove();
            }
        };
        try {
            Future<Integer> one = pool.submit(attempt);
            Future<Integer> two = pool.submit(attempt);
            assertEquals(609, one.get(10, TimeUnit.SECONDS) + two.get(10, TimeUnit.SECONDS));
            verify(logs, times(1)).record(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any());
            verify(notifications, times(1)).createNotification(any());
            verify(cache, times(1)).evictAfterCommit(4L);
        } finally {
            pool.shutdownNow();
        }
    }

    void rejected(int code) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.handoverDisabledAssignee(10L, dto)).getCode());
        verify(tasks, never()).handoverDisabledAssigneeIfCurrent(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(logs, notifications, cache);
    }
}
