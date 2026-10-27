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
        assertEquals(1, fixture.count("notification"));
        assertEquals("TASK_OVERDUE", jdbc.queryForObject(
            "SELECT type FROM notification WHERE project_id = ?", String.class, fixture.projectId));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
