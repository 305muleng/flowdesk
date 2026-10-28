package com.flowdesk;

import com.flowdesk.mapper.*;
import com.flowdesk.service.*;
import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
class TaskReminderIntegrationTest {
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskReminderMapper reminders;
    @Autowired ProjectMapper projects;
    @Autowired TaskMapper tasks;
    @Autowired NotificationMapper notifications;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    ProjectFixture fixture;
    TaskReminderService service;
    RedisReminderStream stream;
    String key;
    long taskId;

    @BeforeEach
    void setup() {
        fixture = ProjectFixture.create(jdbc);
        key = "flowdesk:test:reminders:" + UUID.randomUUID();
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        stream = new RedisReminderStream(redis, key);
        var proxy = new ProxyFactory(new TaskReminderService(reminders, projects, tasks, notifications, events, true));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (TaskReminderService) proxy.getProxy();
        taskId = fixture.task(fixture.developer, LocalDateTime.now().plusHours(12).withNano(0));
    }

    @AfterEach
    void cleanup() {
        try {
            if (key != null) {
                redis.delete(key);
                assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
            }
        } finally { if (fixture != null) fixture.close(); }
    }

    private long plan() {
        service.planTask(taskId, LocalDateTime.now().withNano(0));
        return jdbc.queryForObject("SELECT id FROM task_reminder_event WHERE task_id = ? AND state = 'PENDING'", Long.class, taskId);
    }

    private String state(long event) {
        return jdbc.queryForObject("SELECT state FROM task_reminder_event WHERE id = ?", String.class, event);
    }

    @Test
    void committedNotificationSurvivesConsumerRestartAndPendingMessageIsDeduplicated() {
        long event = plan();
        stream.publish(event);
        var message = stream.read().get(0);
        service.deliver(event);
        assertEquals("DONE", state(event));
        assertEquals(1, fixture.count("notification"));

        // 数据库已提交，消费者在确认消息前退出。
        redis.execute((RedisCallback<Object>) connection -> connection.execute("XCLAIM",
            bytes(key), bytes("task-reminders"), bytes("stopped-consumer"), bytes("0"),
            bytes(message.id()), bytes("IDLE"), bytes("61000")));
        var restarted = new RedisReminderStream(redis, key);
        var reclaimed = restarted.reclaim();
        assertEquals(1, reclaimed.size());
        service.deliver(event);
        restarted.acknowledge(reclaimed.get(0).id());
        assertEquals(1, fixture.count("notification"));
        assertEquals(0L, redis.opsForStream().size(key));
        assertEquals(0L, redis.opsForStream().pending(key, "task-reminders").getTotalPendingMessages());
    }

    @Test
    void notificationAndEventCompletionRollBackTogether() {
        long event = plan();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.deliver(event);
            assertEquals(1, fixture.count("notification"));
            throw new IllegalStateException("模拟提交前失败");
        }));
        assertEquals("PENDING", state(event));
        assertEquals(0, fixture.count("notification"));
        service.deliver(event);
        assertEquals("DONE", state(event));
        assertEquals(1, fixture.count("notification"));
    }

    @Test
    void reschedulingInvalidatesOldEventAndCreatesANewRevision() {
        long old = plan();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertNotNull(projects.selectByIdForUpdate(fixture.projectId));
            jdbc.update("UPDATE task SET deadline = ? WHERE id = ?", LocalDateTime.now().plusHours(20).withNano(0), taskId);
            service.syncTask(taskId);
        });
        service.deliver(old);
        assertEquals("CANCELLED", state(old));
        assertEquals(0, fixture.count("notification"));
        long current = plan();
        assertNotEquals(old, current);
        service.deliver(current);
        assertEquals("DONE", state(current));
        assertEquals(1, fixture.count("notification"));
    }

    @Test
    void databaseOutboxRepublishesAfterRedisStreamLoss() {
        long event = plan();
        var jobs = new TaskReminderJobs(reminders, service, stream);
        jobs.publish();
        assertEquals(1L, redis.opsForStream().size(key));
        redis.delete(key);
        jdbc.update("UPDATE task_reminder_event SET next_publish_at = ? WHERE id = ?", LocalDateTime.now().minusSeconds(1), event);
        var restarted = new TaskReminderJobs(reminders, service, new RedisReminderStream(redis, key));
        restarted.publish();
        restarted.consume();
        assertEquals("DONE", state(event));
        assertEquals(1, fixture.count("notification"));
    }

    @Test
    void disabledAssigneeFallsBackToTheCurrentEffectiveManager() {
        long event = plan();
        jdbc.update("UPDATE user SET status = 'DISABLED' WHERE id = ?", fixture.developer);
        service.deliver(event);
        assertEquals(fixture.manager, jdbc.queryForObject(
            "SELECT recipient_id FROM notification WHERE project_id = ?", Long.class, fixture.projectId));
    }

    @Test
    void overdueRecoveryDoesNotSendTheOldDueSoonWarning() {
        long old = plan();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertNotNull(projects.selectByIdForUpdate(fixture.projectId));
            jdbc.update("UPDATE task SET deadline = ? WHERE id = ?", LocalDateTime.now().minusHours(1).withNano(0), taskId);
            service.syncTask(taskId);
        });
        service.deliver(old);
        long current = plan();
        assertEquals("OVERDUE", jdbc.queryForObject("SELECT kind FROM task_reminder_event WHERE id = ?", String.class, current));
        service.deliver(current);
        assertEquals(2, fixture.count("notification"));
        assertEquals("TASK_OVERDUE", jdbc.queryForObject(
            "SELECT DISTINCT type FROM notification WHERE project_id = ?", String.class, fixture.projectId));
    }

    @Test
    void overdueNotifiesAssigneeAndCurrentManagerOnlyOnce() {
        jdbc.update("UPDATE task SET deadline=? WHERE id=?",LocalDateTime.now().minusHours(1).withNano(0),taskId);
        long event=plan();
        jdbc.update("UPDATE project_member SET role='DEVELOPER' WHERE project_id=? AND user_id=?",fixture.projectId,fixture.manager);
        jdbc.update("UPDATE project_member SET role='PROJECT_MANAGER' WHERE project_id=? AND user_id=?",fixture.projectId,fixture.target);
        service.deliver(event);service.deliver(event);
        var recipients=jdbc.queryForList("SELECT recipient_id FROM notification WHERE project_id=?",Long.class,fixture.projectId);
        assertEquals(2,recipients.size());assertEquals(java.util.Set.of(fixture.developer,fixture.target),java.util.Set.copyOf(recipients));
        assertEquals("DONE",state(event));
    }

    @Test
    void overdueAssigneeWhoIsManagerGetsOnlyOneNotification() {
        jdbc.update("UPDATE task SET deadline=?,assignee_id=? WHERE id=?",LocalDateTime.now().minusHours(1).withNano(0),fixture.manager,taskId);
        service.deliver(plan());assertEquals(1,fixture.count("notification"));
        assertEquals(fixture.manager,jdbc.queryForObject("SELECT recipient_id FROM notification WHERE project_id=?",Long.class,fixture.projectId));
    }

    @Test
    void secondRecipientFailureRollsBackBothNotificationAndEvent() {
        jdbc.update("UPDATE task SET deadline=? WHERE id=?",LocalDateTime.now().minusHours(1).withNano(0),taskId);
        long event=plan();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var failing=org.mockito.Mockito.mock(NotificationMapper.class);
        org.mockito.Mockito.when(failing.insert(org.mockito.Mockito.any(com.flowdesk.model.Notification.class))).thenAnswer(call->{
            if(calls.incrementAndGet()==2)throw new IllegalStateException("第二位接收人写入失败");
            return notifications.insert(call.<com.flowdesk.model.Notification>getArgument(0));
        });
        var factory=new ProxyFactory(new TaskReminderService(reminders,projects,tasks,failing,events,true));
        factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        var failingService=(TaskReminderService)factory.getProxy();
        assertThrows(IllegalStateException.class,()->failingService.deliver(event));
        assertEquals(0,fixture.count("notification"));assertEquals("PENDING",state(event));
        service.deliver(event);assertEquals(2,fixture.count("notification"));assertEquals("DONE",state(event));
    }

    @Test
    void extendingAnOverdueTaskKeepsOldNotificationsAndAssignee() {
        var old=LocalDateTime.now().minusHours(1).withNano(0);
        jdbc.update("UPDATE task SET deadline=? WHERE id=?",old,taskId);
        long event=plan();service.deliver(event);assertEquals(2,fixture.count("notification"));
        var next=LocalDateTime.now().plusDays(2).withNano(0);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            projects.selectByIdForUpdate(fixture.projectId);
            jdbc.update("UPDATE task SET deadline=? WHERE id=?",next,taskId);service.syncTask(taskId);
        });
        service.planTask(taskId,LocalDateTime.now());service.deliver(event);
        assertEquals(2,fixture.count("notification"));assertEquals("DONE",state(event));
        assertEquals(2L,jdbc.queryForObject("SELECT revision FROM task_reminder_plan WHERE task_id=?",Long.class,taskId));
        assertEquals(fixture.developer,jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",Long.class,taskId));
        assertTrue(jdbc.queryForObject("SELECT content FROM notification WHERE project_id=? LIMIT 1",String.class,fixture.projectId).contains(old.toString()));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
