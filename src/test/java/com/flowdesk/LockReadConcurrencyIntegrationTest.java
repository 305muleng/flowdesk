package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.service.ProjectInvitationService;
import com.flowdesk.service.TaskService;
import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
class LockReadConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectMapper projects;
    @Autowired TaskService tasks;
    @Autowired ProjectInvitationService invitations;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void reassignmentReadsTheAssigneeCommittedDuringLockWait() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            long task = f.task(f.developer, future());
            var dto = new AssignTaskDTO();
            dto.setAssigneeId(f.developer);
            race(f, f.manager,
                () -> jdbc.update("UPDATE task SET assignee_id = ? WHERE id = ?", f.target, task),
                () -> { tasks.assignTask(task, dto); return null; });

            assertEquals(f.developer, jdbc.queryForObject("SELECT assignee_id FROM task WHERE id = ?", Long.class, task));
            assertEquals(String.valueOf(f.target), beforeValue(f, "ASSIGN_TASK", "assigneeId"));
            assertEquals(1, f.count("operation_log"));
        }
    }

    @Test
    void cancellationLogsTheStateCommittedDuringLockWait() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            long task = f.task(f.developer, future());
            var dto = new CancelTaskDTO();
            dto.setReason("取消并发测试任务");
            race(f, f.manager,
                () -> jdbc.update("UPDATE task SET status = 'IN_PROGRESS' WHERE id = ?", task),
                () -> { tasks.cancelTask(task, dto); return null; });

            assertEquals("CANCELLED", taskStatus(task));
            assertEquals("IN_PROGRESS", beforeValue(f, "CANCEL_TASK", "status"));
        }
    }

    @Test
    void invitationCommittedDuringLockWaitPreventsDuplicate() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            removeTargetMembership(f);
            String username = username(f.target);
            var dto = new SendInvitationDTO();
            dto.setUsername(username);
            var error = assertThrows(ExecutionException.class, () -> race(f, f.manager,
                () -> invitation(f, LocalDateTime.now()), () -> invitations.sendInvitation(f.projectId, dto)));

            assertEquals(409, businessError(error).getCode());
            assertEquals(1L, invitationCount(f));
            assertEquals(0, f.count("operation_log"));
            assertEquals(0, f.count("notification"));
        }
    }

    @Test
    void memberJoinedDuringLockWaitCannotReceiveInvitation() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            removeTargetMembership(f);
            String username = username(f.target);
            var dto = new SendInvitationDTO();
            dto.setUsername(username);
            var error = assertThrows(ExecutionException.class, () -> race(f, f.manager,
                () -> jdbc.update("""
                    INSERT INTO project_member (project_id, user_id, role, status)
                    VALUES (?, ?, 'DEVELOPER', 'ACTIVE')
                    """, f.projectId, f.target), () -> invitations.sendInvitation(f.projectId, dto)));

            assertEquals(409, businessError(error).getCode());
            assertEquals(0L, invitationCount(f));
            assertEquals(0, f.count("operation_log"));
        }
    }

    @Test
    void rejectionUsesTheAccountDisabledDuringLockWait() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            long task = f.task(f.developer, future());
            jdbc.update("UPDATE task SET status = 'REVIEW' WHERE id = ?", task);
            long submission = submission(f, task, 1, "PENDING");
            var dto = new ReviewTaskDTO();
            dto.setAction("REJECT");
            dto.setReviewNote("请修改后重新提交");
            race(f, f.manager,
                () -> jdbc.update("UPDATE user SET status = 'DISABLED' WHERE id = ?", f.developer),
                () -> { tasks.reviewTask(task, dto); return null; });

            assertEquals("TODO", taskStatus(task));
            assertNull(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id = ?", Long.class, task));
            assertEquals("REJECTED", submissionStatus(submission));
            assertEquals(1, f.count("operation_log"));
        }
    }

    @Test
    void reviewReadsThePendingRoundCommittedDuringLockWait() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            long task = f.task(f.developer, future());
            jdbc.update("UPDATE task SET status = 'REVIEW' WHERE id = ?", task);
            long previous = submission(f, task, 1, "PENDING");
            var dto = new ReviewTaskDTO();
            dto.setAction("APPROVE");
            race(f, f.manager, () -> {
                jdbc.update("UPDATE task_submission SET review_status = 'REJECTED' WHERE id = ?", previous);
                submission(f, task, 2, "PENDING");
            }, () -> { tasks.reviewTask(task, dto); return null; });

            assertEquals("DONE", taskStatus(task));
            assertEquals("REJECTED", submissionStatus(previous));
            assertEquals("APPROVED", jdbc.queryForObject("""
                SELECT review_status FROM task_submission WHERE task_id = ? AND submission_no = 2
                """, String.class, task));
            assertEquals(1, f.count("operation_log"));
        }
    }

    @Test
    void resubmissionReadsTheNumberCommittedDuringLockWait() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            long task = f.task(f.developer, future());
            jdbc.update("UPDATE task SET status = 'IN_PROGRESS' WHERE id = ?", task);
            var dto = new SubmitTaskDTO();
            dto.setCompletionNote("已完成修改");
            dto.setTestNote("已完成自测");
            Long created = race(f, f.developer,
                () -> submission(f, task, 1, "REJECTED"), () -> tasks.submitTask(task, dto));

            assertEquals("REVIEW", taskStatus(task));
            assertEquals(2, jdbc.queryForObject("SELECT submission_no FROM task_submission WHERE id = ?", Integer.class, created));
            assertEquals("PENDING", submissionStatus(created));
            assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM task_submission WHERE task_id = ?", Long.class, task));
        }
    }

    @Test
    void invitationAcceptanceRechecksTheAccountAfterLockWait() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            removeTargetMembership(f);
            long invitation = invitation(f, LocalDateTime.now());
            var error = assertThrows(ExecutionException.class, () -> race(f, f.target,
                () -> jdbc.update("UPDATE user SET status = 'DISABLED' WHERE id = ?", f.target),
                () -> { invitations.acceptInvitation(invitation); return null; }));

            assertEquals(403, businessError(error).getCode());
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM project_invitation WHERE id = ?", String.class, invitation));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM project_member WHERE project_id = ? AND user_id = ?",
                Long.class, f.projectId, f.target));
            assertEquals(0, f.count("operation_log"));
            assertEquals(0, f.count("notification"));
        }
    }

    @Test
    void invitationAcceptanceRejectsAnOldInvitationAfterMembershipChanged() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            jdbc.update("UPDATE project_member SET status = 'INACTIVE', joined_at = ?, left_at = ? WHERE project_id = ? AND user_id = ?",
                LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(2), f.projectId, f.target);
            long invitation = invitation(f, LocalDateTime.now().minusDays(1));
            var error = assertThrows(ExecutionException.class, () -> race(f, f.target,
                () -> jdbc.update("UPDATE project_member SET joined_at = ?, left_at = ? WHERE project_id = ? AND user_id = ?",
                    LocalDateTime.now(), LocalDateTime.now(), f.projectId, f.target),
                () -> { invitations.acceptInvitation(invitation); return null; }));

            assertEquals(409, businessError(error).getCode());
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM project_invitation WHERE id = ?", String.class, invitation));
            assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM project_member WHERE project_id = ? AND user_id = ?",
                String.class, f.projectId, f.target));
            assertEquals(0, f.count("operation_log"));
        }
    }

    private long invitation(ProjectFixture f, LocalDateTime createdAt) {
        return f.insert("""
            INSERT INTO project_invitation (project_id, inviter_id, invitee_id, status, created_at, expires_at)
            VALUES (?, ?, ?, 'PENDING', ?, ?)
            """, f.projectId, f.manager, f.target, createdAt, future());
    }

    private long submission(ProjectFixture f, long task, int number, String state) {
        return f.insert("""
            INSERT INTO task_submission (task_id, submitter_id, submission_no, completion_note, test_note, review_status, submitted_at)
            VALUES (?, ?, ?, '完成记录', '自测通过', ?, NOW())
            """, task, f.developer, number, state);
    }

    private void removeTargetMembership(ProjectFixture f) {
        assertEquals(1, jdbc.update("DELETE FROM project_member WHERE project_id = ? AND user_id = ?", f.projectId, f.target));
    }

    private long invitationCount(ProjectFixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM project_invitation WHERE project_id = ?", Long.class, f.projectId);
    }

    private String username(long user) {
        return jdbc.queryForObject("SELECT username FROM user WHERE id = ?", String.class, user);
    }

    private String taskStatus(long task) {
        return jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, task);
    }

    private String submissionStatus(long submission) {
        return jdbc.queryForObject("SELECT review_status FROM task_submission WHERE id = ?", String.class, submission);
    }

    private String beforeValue(ProjectFixture f, String action, String field) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(before_data, ?)) FROM operation_log WHERE project_id = ? AND action = ?",
            String.class, "$." + field, f.projectId, action);
    }

    private LocalDateTime future() {
        return LocalDateTime.now().plusDays(1).withNano(0);
    }

    private BusinessException businessError(ExecutionException error) {
        return assertInstanceOf(BusinessException.class, error.getCause());
    }

    private TransactionTemplate repeatableRead() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private <T> T race(ProjectFixture f, long operator, Runnable mutation, Supplier<T> action) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var snapshot = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> repeatableRead().executeWithoutResult(status -> {
                assertNotNull(projects.selectByIdForUpdate(f.projectId));
                mutation.run();
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "写入事务未获取项目锁");
            Future<T> reader = pool.submit(() -> {
                UserContext.set(new CurrentUser(operator, "USER"));
                try {
                    return repeatableRead().execute(status -> {
                        // 在另一事务提交前建立旧快照，再调用真实的业务服务。
                        assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE project_id = ?", Long.class, f.projectId));
                        snapshot.countDown();
                        return action.get();
                    });
                } finally { UserContext.remove(); }
            });
            assertTrue(snapshot.await(10, TimeUnit.SECONDS), "读取事务未建立快照");
            assertThrows(TimeoutException.class, () -> reader.get(250, TimeUnit.MILLISECONDS), "业务请求应等待项目锁");
            release.countDown();
            writer.get(15, TimeUnit.SECONDS);
            return reader.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发检查线程没有退出");
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("等待并发检查超时");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
