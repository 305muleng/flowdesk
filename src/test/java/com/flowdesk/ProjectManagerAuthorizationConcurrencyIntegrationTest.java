package com.flowdesk;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.flowdesk.config.MybatisPlusConfig;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.RemoveProjectMemberDTO;
import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.enums.UserStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.User;
import com.flowdesk.service.*;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL transactions and real Spring service proxy; deliberately no Redis or web context. */
@SpringJUnitConfig(ProjectManagerAuthorizationConcurrencyIntegrationTest.DatabaseConfig.class)
@TestPropertySource("classpath:application-test.properties")
class ProjectManagerAuthorizationConcurrencyIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
    @MapperScan(basePackages = "com.flowdesk.mapper", annotationClass = Mapper.class)
    @Import({MybatisPlusConfig.class, ProjectService.class, OperationLogService.class,
            NotificationService.class, ProjectRequestClosureService.class})
    static class DatabaseConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ObservedPermissions permissions(ProjectMemberMapper members, JdbcTemplate jdbc) {
            return new ObservedPermissions(members, jdbc);
        }
    }

    // The hook only observes successful REAL authorization and the existing RR snapshot.
    // All mapper calls, database locks, role checks and business writes remain real.
    static class ObservedPermissions extends ProjectPermissionService {
        private final JdbcTemplate jdbc;
        volatile Long observedUserId;
        volatile CountDownLatch initialCheck;
        final AtomicLong readerConnection = new AtomicLong();

        ObservedPermissions(ProjectMemberMapper members, JdbcTemplate jdbc) {
            super(members);
            this.jdbc = jdbc;
        }

        @Override
        public ProjectMember requireProjectManager(Long projectId) {
            ProjectMember member = super.requireProjectManager(projectId);
            if (UserContext.get().getUserId().equals(observedUserId)) {
                assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                readerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                initialCheck.countDown();
            }
            return member;
        }

        @Override
        public ProjectMember requireProjectManagerForUpdate(Long projectId) {
            if (UserContext.get().getUserId().equals(observedUserId)) {
                // Even AFTER the transfer commits, a normal read still sees the old role.
                assertEquals("PROJECT_MANAGER", jdbc.queryForObject(
                        "SELECT role FROM project_member WHERE project_id = ? AND user_id = ?",
                        String.class, projectId, observedUserId));
            }
            // SELECT ... FOR UPDATE must instead read the committed DEVELOPER role and reject.
            return super.requireProjectManagerForUpdate(projectId);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired ProjectMapper projects;
    @Autowired ProjectMemberMapper members;
    @Autowired ProjectService projectService;
    @Autowired ObservedPermissions permissions;
    @Autowired PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(strings = {"start", "remove"})
    void committedTransferRejectsFormerManagerButAllowsCurrentManager(String operation) throws Exception {
        // Guard the ACTUAL connection before creating any data. Never fall back to the application DB.
        assertEquals("flowdesk_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        String account = jdbc.queryForObject("SELECT CURRENT_USER()", String.class);
        assertNotNull(account);
        assertTrue(account.startsWith("flowdesk_test_user@"), "Only the dedicated test account may run this test");
        for (String table : List.of("project", "project_member", "user")) {
            assertEquals("InnoDB", jdbc.queryForObject(
                    "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    String.class, table));
        }

        List<Long> userIds = new ArrayList<>();
        Long projectId = null;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch initialCheck = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        try {
            long formerManager = createUser(userIds);
            long currentManager = createUser(userIds);
            long targetDeveloper = createUser(userIds);
            Project project = new Project();
            project.setCreatorId(formerManager);
            project.setName("manager-auth-race-" + UUID.randomUUID());
            project.setDescription("Dedicated authorization regression fixture");
            project.setGoal("Verify locked current-read authorization");
            project.setStatus(ProjectStatus.PREPARING);
            project.setExpectedEndTime(LocalDateTime.now().plusDays(30));
            project.setCreatedAt(LocalDateTime.now());
            project.setUpdatedAt(LocalDateTime.now());
            assertEquals(1, projects.insert(project));
            projectId = project.getId();
            final long pid = projectId;
            createMember(pid, formerManager, "PROJECT_MANAGER");
            createMember(pid, currentManager, "DEVELOPER");
            createMember(pid, targetDeveloper, "DEVELOPER");

            permissions.observedUserId = formerManager;
            permissions.initialCheck = initialCheck;

            Future<?> transfer = pool.submit(() -> repeatableRead().executeWithoutResult(status -> {
                assertNotNull(projects.selectByIdForUpdate(pid));
                writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                writerLocked.countDown();
                await(releaseWriter, "release the role transfer");
                // Use the same conditional mapper updates as STAY acceptance, under the project lock.
                assertEquals(1, members.demoteManagerToDeveloper(pid, formerManager, LocalDateTime.now()));
                assertEquals(1, members.promoteDeveloperToManager(pid, currentManager, LocalDateTime.now()));
            }));
            assertTrue(writerLocked.await(10, TimeUnit.SECONDS), "Transfer did not acquire project lock");

            Future<?> staleRequest = pool.submit(() -> {
                UserContext.set(new CurrentUser(formerManager, "USER"));
                try {
                    repeatableRead().executeWithoutResult(status -> invoke(operation, pid, targetDeveloper));
                } finally {
                    UserContext.remove();
                }
            });
            assertTrue(initialCheck.await(10, TimeUnit.SECONDS), "Initial real manager check did not pass");
            assertNotEquals(writerConnection.get(), permissions.readerConnection.get(), "Transactions must use different connections");
            assertThrows(TimeoutException.class, () -> staleRequest.get(300, TimeUnit.MILLISECONDS),
                    "The request must wait while the transfer holds the project lock");
            releaseWriter.countDown();
            transfer.get(15, TimeUnit.SECONDS); // A committed transfer is a prerequisite for the 403 assertion.

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> staleRequest.get(15, TimeUnit.SECONDS));
            assertEquals(403, assertInstanceOf(BusinessException.class, error.getCause()).getCode());
            assertEquals("DEVELOPER", role(pid, formerManager));
            assertEquals("PROJECT_MANAGER", role(pid, currentManager));
            assertEquals("PREPARING", jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, pid));
            assertEquals("ACTIVE", memberStatus(pid, targetDeveloper));
            assertEquals(0L, count("operation_log", pid));
            assertEquals(0L, count("notification", pid));

            // Positive control: the current manager can perform the SAME operation through the Spring proxy.
            UserContext.set(new CurrentUser(currentManager, "USER"));
            try {
                invoke(operation, pid, targetDeveloper);
            } finally {
                UserContext.remove();
            }
            assertEquals(1L, count("operation_log", pid));
            assertEquals(currentManager, jdbc.queryForObject(
                    "SELECT actor_id FROM operation_log WHERE project_id = ?", Long.class, pid));
            if (operation.equals("start")) {
                assertEquals("IN_PROGRESS", jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, pid));
                assertNotNull(jdbc.queryForObject("SELECT start_time FROM project WHERE id = ?", LocalDateTime.class, pid));
                assertEquals("ACTIVE", memberStatus(pid, targetDeveloper));
                assertEquals(0L, count("notification", pid));
            } else {
                assertEquals("INACTIVE", memberStatus(pid, targetDeveloper));
                assertEquals(1L, count("notification", pid));
                assertEquals(targetDeveloper, jdbc.queryForObject(
                        "SELECT recipient_id FROM notification WHERE project_id = ?", Long.class, pid));
            }
        } finally {
            releaseWriter.countDown();
            pool.shutdown();
            boolean stopped = pool.awaitTermination(35, TimeUnit.SECONDS);
            if (!stopped) {
                pool.shutdownNow();
                stopped = pool.awaitTermination(15, TimeUnit.SECONDS);
            }
            // Do not race cleanup against a still-running database writer.
            assertTrue(stopped, "Workers must stop before fixture cleanup");
            permissions.observedUserId = null;
            permissions.initialCheck = null;
            UserContext.remove();
            final Long cleanupProjectId = projectId;
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (cleanupProjectId != null) {
                    jdbc.update("DELETE FROM notification WHERE project_id = ?", cleanupProjectId);
                    jdbc.update("DELETE FROM operation_log WHERE project_id = ?", cleanupProjectId);
                    jdbc.update("DELETE FROM project_member WHERE project_id = ?", cleanupProjectId);
                    jdbc.update("DELETE FROM project WHERE id = ?", cleanupProjectId);
                }
                for (Long userId : userIds) {
                    assertEquals(1, jdbc.update("DELETE FROM `user` WHERE id = ?", userId));
                }
            });
            if (projectId != null) {
                for (String table : List.of("notification", "operation_log", "project_member")) {
                    assertEquals(0L, count(table, projectId), "Fixture cleanup failed: " + table);
                }
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE id = ?", Long.class, projectId));
            }
            for (Long userId : userIds) {
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id = ?", Long.class, userId));
            }
        }
    }

    private TransactionTemplate repeatableRead() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }

    private void invoke(String operation, long projectId, long targetId) {
        if (operation.equals("start")) {
            projectService.startProject(projectId);
        } else {
            projectService.removeProjectMember(projectId, targetId, new RemoveProjectMemberDTO());
        }
    }

    private long createUser(List<Long> userIds) {
        User user = new User();
        user.setUsername("authrace_" + UUID.randomUUID());
        user.setPasswordHash("integration-test-only");
        user.setRealName("权限竞争测试用户");
        user.setSystemRole("USER");
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        assertEquals(1, users.insert(user));
        userIds.add(user.getId());
        return user.getId();
    }

    private void createMember(long projectId, long userId, String role) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(ProjectMemberStatus.ACTIVE);
        member.setJoinedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());
        assertEquals(1, members.insert(member));
    }

    private String role(long projectId, long userId) {
        return jdbc.queryForObject("SELECT role FROM project_member WHERE project_id = ? AND user_id = ?",
                String.class, projectId, userId);
    }

    private String memberStatus(long projectId, long userId) {
        return jdbc.queryForObject("SELECT status FROM project_member WHERE project_id = ? AND user_id = ?",
                String.class, projectId, userId);
    }

    private long count(String table, long projectId) {
        // table is always a test-owned literal, never application input.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE project_id = ?", Long.class, projectId);
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to " + description);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
