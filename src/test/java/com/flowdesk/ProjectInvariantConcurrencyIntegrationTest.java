package com.flowdesk;

import com.flowdesk.context.*;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.service.*;
import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
class ProjectInvariantConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectMapper projects;
    @Autowired ProjectService projectService;
    @Autowired MemberLeaveRequestService leaves;
    @Autowired ProjectManagerTransferService transfers;
    @Autowired TaskRequestService requests;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(strings = {"remove", "reviewLeave", "acceptTransfer"})
    void unfinishedTasksCommittedDuringLockWaitMustBlockLeaving(String operation) throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            long requestId;
            long operator;
            long assignee;
            if (operation.equals("reviewLeave")) {
                requestId = f.insert("""
                    INSERT INTO member_leave_request (project_id, applicant_id, reason, status, expires_at)
                    VALUES (?, ?, '测试退出', 'PENDING', ?)
                    """, f.projectId, f.developer, LocalDateTime.now().plusDays(1));
                operator = f.manager; assignee = f.developer;
            } else if (operation.equals("acceptTransfer")) {
                requestId = transfer(f);
                operator = f.developer; assignee = f.manager;
            } else {
                requestId = 0; operator = f.manager; assignee = f.developer;
            }
            BusinessException error = race(f,
                () -> f.task(assignee, LocalDateTime.now().plusDays(1)),
                operator,
                () -> {
                    switch (operation) {
                        case "remove" -> projectService.removeProjectMember(f.projectId, f.developer, new RemoveProjectMemberDTO());
                        case "reviewLeave" -> {
                            var dto = new ReviewMemberLeaveRequestDTO(); dto.setAction("APPROVE");
                            leaves.reviewLeaveRequest(f.projectId, requestId, dto);
                        }
                        case "acceptTransfer" -> transfers.acceptTransfer(f.projectId, requestId);
                        default -> throw new IllegalArgumentException();
                    }
                });
            assertEquals(409, error.getCode());
            assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM project_member WHERE project_id = ? AND user_id = ?", String.class, f.projectId, assignee));
            assertEquals(1, f.count("task"));
            assertEquals(0, f.count("operation_log"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfer", "leave"})
    void pendingRequestCommittedDuringLockWaitMustPreventDuplicate(String kind) throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            String target = jdbc.queryForObject("SELECT username FROM user WHERE id = ?", String.class, f.target);
            var dto = new CreateProjectManagerTransferDTO();
            dto.setTargetUsername(target); dto.setOldManagerAction("STAY"); dto.setCurrentPassword("test-only");
            BusinessException error = kind.equals("transfer")
                ? race(f, () -> transfer(f), f.manager, () -> transfers.createTransfer(f.projectId, dto))
                : race(f, () -> f.insert("""
                    INSERT INTO member_leave_request (project_id, applicant_id, reason, status, expires_at)
                    VALUES (?, ?, '测试退出', 'PENDING', ?)
                    """, f.projectId, f.developer, LocalDateTime.now().plusDays(1)), f.developer,
                    () -> {
                        var leave = new CreateMemberLeaveRequestDTO(); leave.setReason("测试退出");
                        leaves.createLeaveRequest(f.projectId, leave);
                    });
            assertEquals(409, error.getCode());
            assertEquals(1, f.count(kind.equals("transfer") ? "project_manager_transfer" : "member_leave_request"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"taskRequest", "leaveRequest"})
    void removedDeveloperCannotCreateRequestsAfterWaitingForProjectLock(String operation) throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            BusinessException error = race(f,
                () -> jdbc.update("UPDATE project_member SET status = 'INACTIVE', left_at = NOW() WHERE project_id = ? AND user_id = ?",
                    f.projectId, f.developer), f.developer,
                () -> {
                    if (operation.equals("taskRequest")) {
                        var dto = new CreateTaskRequestDTO();
                        dto.setTitle("回归申请"); dto.setDescription("描述"); dto.setGoal("目标");
                        requests.createTaskRequest(f.projectId, dto);
                    } else {
                        var dto = new CreateMemberLeaveRequestDTO(); dto.setReason("测试退出");
                        leaves.createLeaveRequest(f.projectId, dto);
                    }
                });
            assertEquals(403, error.getCode());
            assertEquals(0, f.count("task_request"));
            assertEquals(0, f.count("member_leave_request"));
            assertEquals(0, f.count("operation_log"));
        }
    }

    private long transfer(ProjectFixture f) {
        return f.insert("""
            INSERT INTO project_manager_transfer (project_id, from_user_id, to_user_id, old_manager_action, status, expires_at)
            VALUES (?, ?, ?, 'LEAVE', 'PENDING', ?)
            """, f.projectId, f.manager, f.developer, LocalDateTime.now().plusDays(1));
    }

    private TransactionTemplate rr() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }

    private BusinessException race(ProjectFixture f, Runnable mutation, long operator, Runnable request) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var snapshot = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> rr().executeWithoutResult(status -> {
                assertNotNull(projects.selectByIdForUpdate(f.projectId));
                mutation.run();
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<?> reader = pool.submit(() -> {
                UserContext.set(new CurrentUser(operator, "USER"));
                try {
                    rr().executeWithoutResult(status -> {
                        // 普通查询先建立旧快照，后面的项目锁不能刷新它。
                        assertEquals(0, f.count("task"));
                        snapshot.countDown();
                        request.run();
                    });
                } finally { UserContext.remove(); }
            });
            assertTrue(snapshot.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> reader.get(250, TimeUnit.MILLISECONDS));
            release.countDown();
            writer.get(15, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> reader.get(15, TimeUnit.SECONDS));
            return assertInstanceOf(BusinessException.class, failure.getCause());
        } finally {
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("等待并发测试超时"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
