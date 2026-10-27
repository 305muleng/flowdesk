package com.flowdesk;

import com.flowdesk.enums.*;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.TaskReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskReminderServiceTest {
    TaskReminderMapper reminders = mock(TaskReminderMapper.class);
    ProjectMapper projects = mock(ProjectMapper.class);
    TaskMapper tasks = mock(TaskMapper.class);
    NotificationMapper notifications = mock(NotificationMapper.class);
    org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
    TaskReminderService service = new TaskReminderService(reminders, projects, tasks, notifications, events, true);
    Project project;
    Task task;
    LocalDateTime now = LocalDateTime.of(2026, 10, 25, 10, 0);

    @BeforeEach
    void setup() {
        project = new Project(); project.setId(4L); project.setStatus(ProjectStatus.IN_PROGRESS);
        task = new Task(); task.setId(10L); task.setProjectId(4L); task.setStatus(TaskStatus.TODO);
        task.setTitle("交付接口"); task.setAssigneeId(2L); task.setDeadline(LocalDateTime.now().plusHours(12).withNano(0));
        when(tasks.selectById(10L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(10L)).thenAnswer(i -> task);
        when(projects.selectByIdForUpdate(4L)).thenAnswer(i -> project);
        when(reminders.lockPlan(10L)).thenAnswer(i -> new TaskReminderPlan(10L, 4L, task.getDeadline(), task.getAssigneeId(), 1));
        when(reminders.findEvent(20L)).thenReturn(event("DUE_SOON", "PENDING", 1));
        when(reminders.lockEvent(20L)).thenReturn(event("DUE_SOON", "PENDING", 1));
        when(reminders.effectiveAssignee(4L, 2L)).thenReturn(2L);
        when(notifications.insert(any(Notification.class))).thenReturn(1);
        when(reminders.finish(eq(20L), anyString(), any())).thenReturn(1);
    }

    private TaskReminderEvent event(String kind, String state, long revision) {
        return new TaskReminderEvent(20L, 10L, 4L, revision, kind, state, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {25, 24, 12, 0, -1})
    void plansAtTheWindowAndDeadlineBoundaries(int hours) {
        task.setDeadline(now.plusHours(hours));
        service.planTask(10L, now);
        verify(reminders).syncPlan(10L, 4L, task.getDeadline(), 2L);
        if (hours > 24) verify(reminders, never()).createEvent(anyLong(), anyLong(), anyLong(), anyString(), any());
        else verify(reminders).createEvent(10L, 4L, 1, hours <= 0 ? "OVERDUE" : "DUE_SOON", now);
    }

    @Test
    void preparingTasksGetDurablePlansWithoutEvents() {
        project.setStatus(ProjectStatus.PREPARING);
        service.planTask(10L, now);
        verify(reminders).syncPlan(10L, 4L, task.getDeadline(), 2L);
        verify(reminders, never()).createEvent(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void disabledModuleDoesNotAccessReminderOrTaskTables() {
        new TaskReminderService(reminders, projects, tasks, notifications, events, false).syncTask(10L);
        verifyNoInteractions(reminders, tasks, projects, notifications);
    }

    @Test
    void planSynchronizationUsesLockedCurrentTask() {
        service.syncTask(10L);
        var order = inOrder(tasks, reminders);
        order.verify(tasks).selectByIdForUpdate(10L);
        order.verify(reminders).syncPlan(10L, 4L, task.getDeadline(), 2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TODO", "IN_PROGRESS"})
    void activeTasksReceiveOneSystemNotification(String status) {
        task.setStatus(TaskStatus.fromCode(status));
        service.deliver(20L);
        var notification = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).insert(notification.capture());
        assertEquals(2L, notification.getValue().getRecipientId());
        assertNull(notification.getValue().getActorId());
        assertEquals("TASK_DUE_SOON", notification.getValue().getType());
        assertEquals("TASK", notification.getValue().getTargetType());
        assertEquals(10L, notification.getValue().getTargetId());
        verify(reminders).finish(eq(20L), eq("DONE"), any());
        verify(reminders, never()).effectiveManager(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVIEW", "DONE", "CANCELLED"})
    void submittedOrTerminalTasksInvalidateQueuedMessages(String status) {
        task.setStatus(TaskStatus.fromCode(status));
        service.deliver(20L);
        verifyNoInteractions(notifications);
        verify(reminders).finish(eq(20L), eq("CANCELLED"), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARING", "PENDING_ACCEPTANCE", "COMPLETED", "ARCHIVED", "CANCELLED"})
    void inactiveProjectsDoNotSendReminders(String status) {
        project.setStatus(ProjectStatus.fromCode(status));
        service.deliver(20L);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVISION", "DEADLINE", "ASSIGNEE", "DELETED_TASK", "DELETED_PROJECT", "MISSING_TASK"})
    void obsoleteOrDeletedTargetsCannotReceiveQueuedReminders(String reason) {
        if (reason.equals("REVISION")) when(reminders.lockEvent(20L)).thenReturn(event("DUE_SOON", "PENDING", 2));
        if (reason.equals("DEADLINE")) when(reminders.lockPlan(10L)).thenReturn(new TaskReminderPlan(10L, 4L, task.getDeadline().minusHours(1), 2L, 1));
        if (reason.equals("ASSIGNEE")) when(reminders.lockPlan(10L)).thenReturn(new TaskReminderPlan(10L, 4L, task.getDeadline(), 3L, 1));
        if (reason.equals("DELETED_TASK")) task.setDeletedAt(LocalDateTime.now());
        if (reason.equals("DELETED_PROJECT")) project.setDeletedAt(LocalDateTime.now());
        if (reason.equals("MISSING_TASK")) {
            when(tasks.selectByIdForUpdate(10L)).thenReturn(null);
            when(reminders.lockPlan(10L)).thenReturn(null);
        }
        service.deliver(20L);
        verifyNoInteractions(notifications);
        verify(reminders).finish(eq(20L), eq("CANCELLED"), any());
    }

    @Test
    void unassignedAndDisabledAssigneesFallBackToCurrentManager() {
        when(reminders.effectiveAssignee(4L, 2L)).thenReturn(null);
        when(reminders.effectiveManager(4L)).thenReturn(3L);
        service.deliver(20L);
        var notification = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).insert(notification.capture());
        assertEquals(3L, notification.getValue().getRecipientId());
    }

    @Test
    void absentAssigneeUsesManagerOnce() {
        task.setAssigneeId(null);
        when(reminders.effectiveManager(4L)).thenReturn(3L);
        service.deliver(20L);
        verify(reminders, never()).effectiveAssignee(anyLong(), anyLong());
        verify(notifications, times(1)).insert(any(Notification.class));
    }

    @Test
    void missingRecipientPreservesEventForRetry() {
        when(reminders.effectiveAssignee(4L, 2L)).thenReturn(null);
        when(reminders.effectiveManager(4L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.deliver(20L));
        verifyNoInteractions(notifications);
        verify(reminders, never()).finish(anyLong(), anyString(), any());
    }

    @Test
    void expiredDueSoonMessageDoesNotAlsoSendAnOutdatedWarning() {
        task.setDeadline(LocalDateTime.now().minusMinutes(1).withNano(0));
        service.deliver(20L);
        verifyNoInteractions(notifications);
        verify(reminders).finish(eq(20L), eq("CANCELLED"), any());
        when(reminders.lockEvent(20L)).thenReturn(event("OVERDUE", "PENDING", 1));
        service.deliver(20L);
        var notification = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).insert(notification.capture());
        assertEquals("TASK_OVERDUE", notification.getValue().getType());
    }

    @Test
    void repeatedMessagesConsultTheLockedEventState() {
        when(reminders.lockEvent(20L)).thenReturn(event("DUE_SOON", "PENDING", 1), event("DUE_SOON", "DONE", 1));
        service.deliver(20L); service.deliver(20L);
        verify(notifications, times(1)).insert(any(Notification.class));
    }

    @Test
    void rejectedReviewRestoresUnsentReminderButNeverReopensDeliveredOne() {
        task.setDeadline(LocalDateTime.now().minusHours(1).withNano(0));
        var state = new java.util.concurrent.atomic.AtomicReference<>("PENDING");
        when(reminders.lockEvent(20L)).thenAnswer(i -> event("OVERDUE", state.get(), 1));
        when(reminders.finish(eq(20L), anyString(), any())).thenAnswer(i -> { state.set(i.getArgument(1)); return 1; });
        when(reminders.reopenCancelled(eq(10L), eq(1L), eq("OVERDUE"), any())).thenAnswer(i ->
            state.compareAndSet("CANCELLED", "PENDING") ? 1 : 0);
        task.setStatus(TaskStatus.REVIEW);
        service.deliver(20L);
        assertEquals("CANCELLED", state.get());
        verifyNoInteractions(notifications);
        task.setStatus(TaskStatus.IN_PROGRESS);
        service.planTask(10L, LocalDateTime.now());
        assertEquals("PENDING", state.get());
        service.deliver(20L);
        assertEquals("DONE", state.get());
        service.planTask(10L, LocalDateTime.now());
        service.deliver(20L);
        assertEquals("DONE", state.get());
        verify(notifications, times(1)).insert(any(Notification.class));
    }

    @Test
    void notificationInsertFailureDoesNotFinishTheEvent() {
        when(notifications.insert(any(Notification.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.deliver(20L));
        verify(reminders, never()).finish(anyLong(), anyString(), any());
    }

    @Test
    void transactionProxyRollsBackWhenFinishingEventFails() {
        var tx = new StubTransactionManager();
        var factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        when(reminders.finish(eq(20L), eq("DONE"), any())).thenThrow(new IllegalStateException("事件更新失败"));
        assertThrows(IllegalStateException.class, () -> ((TaskReminderService) factory.getProxy()).deliver(20L));
        assertEquals(1, tx.rollbacks);
        assertEquals(0, tx.commits);
    }
}
