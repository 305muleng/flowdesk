package com.flowdesk;

import com.flowdesk.support.IntegrationTest;

import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.UserStatus;
import com.flowdesk.enums.TaskStatus;
import com.flowdesk.enums.ProjectStatus;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.SubmitProjectAcceptanceDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.ProjectAcceptanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
class ProjectAcceptanceConcurrencyIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserMapper userMapper;

    @Autowired
    ProjectMapper projectMapper;

    @Qualifier("projectMemberMapper")
    @Autowired
    ProjectMemberMapper memberMapper;

    @Autowired
    TaskMapper taskMapper;

    @Autowired
    ProjectAcceptanceService acceptanceService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void newTaskMustBlockProjectAcceptance() throws Exception {
        // 安全保护：绝不能在正式数据库运行
        assertEquals("flowdesk_test", jdbc.queryForObject("SELECT DATABASE()", String.class));

        Long userId = null;
        Long projectId = null;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch taskInserted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch acceptanceStarted = new CountDownLatch(1);

        try {
            LocalDateTime now = LocalDateTime.now();

            // 创建独立测试用户
            User user = new User();
            user.setUsername("race_" + UUID.randomUUID());
            user.setPasswordHash("integration-test-only");
            user.setRealName("并发测试用户");
            user.setSystemRole("USER");
            user.setStatus(UserStatus.ACTIVE);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            userMapper.insert(user);
            userId = user.getId();

            // 创建独立测试项目
            Project project = new Project();
            project.setCreatorId(userId);
            project.setName("并发验收测试");
            project.setDescription("自动化测试项目");
            project.setGoal("验证并发一致性");
            project.setStatus(ProjectStatus.IN_PROGRESS);
            project.setStartTime(now);
            project.setExpectedEndTime(now.plusDays(30));
            project.setCreatedAt(now);
            project.setUpdatedAt(now);
            projectMapper.insert(project);
            projectId = project.getId();

            ProjectMember member = new ProjectMember();
            member.setProjectId(projectId);
            member.setUserId(userId);
            member.setRole("PROJECT_MANAGER");
            member.setStatus(ProjectMemberStatus.ACTIVE);
            member.setJoinedAt(now);
            member.setUpdatedAt(now);
            memberMapper.insert(member);

            final Long pid = projectId;
            final Long uid = userId;

            // 线程 B：锁项目，插入任务，但暂时不提交
            Future<?> writer = pool.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

                tx.execute(status -> {
                    projectMapper.selectByIdForUpdate(pid);

                    Task task = new Task();
                    task.setProjectId(pid);
                    task.setCreatorId(uid);
                    task.setTitle("并发新增任务");
                    task.setDescription("测试任务");
                    task.setGoal("验证验收检查");
                    task.setStatus(TaskStatus.TODO);
                    task.setPriority("MEDIUM");
                    task.setDeadline(LocalDateTime.now().plusDays(2));
                    task.setCreatedAt(LocalDateTime.now());
                    task.setUpdatedAt(LocalDateTime.now());
                    taskMapper.insert(task);

                    taskInserted.countDown();

                    try {
                        if (!releaseWriter.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("等待释放写事务超时");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }

                    return null; // TransactionTemplate 自动提交
                });
            });

            assertTrue(taskInserted.await(5, TimeUnit.SECONDS));

            // 线程 A：调用真实的项目验收 Service
            Future<Integer> acceptance = pool.submit(() -> {
                UserContext.set(new CurrentUser(uid, "USER"));
                acceptanceStarted.countDown();

                try {
                    SubmitProjectAcceptanceDTO dto = new SubmitProjectAcceptanceDTO();
                    dto.setSubmissionNote("并发验收测试");
                    dto.setRepositoryUrl("https://github.com/test/project");

                    acceptanceService.submitAcceptance(pid, dto);
                    return 200;
                } finally {
                    UserContext.remove();
                }
            });

            assertTrue(acceptanceStarted.await(5, TimeUnit.SECONDS), "验收线程未能及时启动");

            // 写事务尚未提交，验收操作不应该提前完成
            try {
                assertThrows(
                    TimeoutException.class,
                    () -> acceptance.get(1, TimeUnit.SECONDS),
                    "写事务持有项目锁时，验收操作不应该提前完成"
                );
            } finally {
                // 无论断言结果如何，都允许写事务释放锁
                releaseWriter.countDown();
            }

            // 确认写事务正常提交
            writer.get(10, TimeUnit.SECONDS);

            ExecutionException error = assertThrows(
                    ExecutionException.class,
                    () -> acceptance.get(10, TimeUnit.SECONDS)
            );

            Throwable cause = error.getCause();

            BusinessException businessException =
                    assertInstanceOf(BusinessException.class, cause);

            assertEquals(409, businessException.getCode());

            assertEquals("IN_PROGRESS", jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, pid));

            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE project_id = ?", Long.class, pid));

            assertEquals(
                0L,
                jdbc.queryForObject("SELECT COUNT(*) FROM project_acceptance WHERE project_id = ?", Long.class, pid)
            );
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

            // 只清理这次测试生成的数据
            if (projectId != null) {
                jdbc.update("DELETE FROM notification WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM operation_log WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project_acceptance WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM task WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project WHERE id = ?", projectId);
            }
            if (userId != null) {
                jdbc.update("DELETE FROM `user` WHERE id = ?", userId);
            }
        }
    }
}
