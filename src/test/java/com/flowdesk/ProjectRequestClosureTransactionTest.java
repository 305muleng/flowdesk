package com.flowdesk;

import org.springframework.boot.test.context.TestConfiguration;

import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.enums.ProjectAcceptanceStatus;

import com.flowdesk.context.*;
import com.flowdesk.dto.*;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 真实 Spring 事务代理 + 事务替身，没有数据库连接，不能证明 InnoDB 回滚或竞争。
class ProjectRequestClosureTransactionTest {

    AnnotationConfigApplicationContext context;
    StubTransactionManager tx;
    ProjectMapper projects;
    ProjectAcceptanceMapper acceptances;
    ProjectInvitationMapper invitations;
    MemberLeaveRequestMapper leaves;
    ProjectManagerTransferMapper transfers;
    TaskRequestMapper tasks;

    @TestConfiguration
    @EnableTransactionManagement
    static class Config {

        @Bean
        StubTransactionManager transactionManager() {
            return new StubTransactionManager();
        }

        @Bean
        ProjectMapper projects() {
            return mock(ProjectMapper.class);
        }

        @Bean
        ProjectAcceptanceMapper acceptances() {
            return mock(ProjectAcceptanceMapper.class);
        }

        @Bean
        ProjectInvitationMapper invitations() {
            return mock(ProjectInvitationMapper.class);
        }

        @Bean
        MemberLeaveRequestMapper leaves() {
            return mock(MemberLeaveRequestMapper.class);
        }

        @Bean
        ProjectManagerTransferMapper transfers() {
            return mock(ProjectManagerTransferMapper.class);
        }

        @Bean
        TaskRequestMapper tasks() {
            return mock(TaskRequestMapper.class);
        }

        @Bean
        ProjectMemberMapper members() {
            return mock(ProjectMemberMapper.class);
        }

        @Bean
        UserMapper users() {
            return mock(UserMapper.class);
        }

        @Bean
        TaskMapper taskMapper() {
            return mock(TaskMapper.class);
        }

        @Bean
        ProjectPermissionService permissions() {
            return mock(ProjectPermissionService.class);
        }

        @Bean
        OperationLogService logs() {
            return mock(OperationLogService.class);
        }

        @Bean
        NotificationService notifications() {
            return mock(NotificationService.class);
        }

        @Bean
        ProjectRequestClosureService closure(
            ProjectInvitationMapper i,
            MemberLeaveRequestMapper l,
            ProjectManagerTransferMapper t,
            TaskRequestMapper r
        ) {
            return new ProjectRequestClosureService(i, l, t, r);
        }

        @Bean
        ProjectService projectService(
            ProjectMapper p,
            ProjectMemberMapper m,
            ProjectPermissionService permission,
            OperationLogService log,
            TaskMapper t,
            TaskRequestMapper r,
            MemberLeaveRequestMapper l,
            NotificationService n,
            UserMapper u,
            ProjectRequestClosureService c
        ) {
            return new ProjectService(p, m, permission, log, t, r, l, n, u, c);
        }

        @Bean
        ProjectAcceptanceService acceptanceService(
            ProjectAcceptanceMapper a,
            ProjectMapper p,
            TaskMapper t,
            ProjectPermissionService permission,
            OperationLogService l,
            TaskRequestMapper r,
            UserMapper u,
            NotificationService n,
            ProjectMemberMapper m,
            ProjectRequestClosureService c
        ) {
            return new ProjectAcceptanceService(a, p, t, permission, l, r, u, n, m, c,
                new com.flowdesk.snapshot.AcceptanceSnapshotCodec(new tools.jackson.databind.ObjectMapper()));
        }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        tx = context.getBean(StubTransactionManager.class);
        projects = context.getBean(ProjectMapper.class);
        acceptances = context.getBean(ProjectAcceptanceMapper.class);
        invitations = context.getBean(ProjectInvitationMapper.class);
        leaves = context.getBean(MemberLeaveRequestMapper.class);
        transfers = context.getBean(ProjectManagerTransferMapper.class);
        tasks = context.getBean(TaskRequestMapper.class);
    }

    @AfterEach
    void cleanup() {
        context.close();
        UserContext.remove();
    }

    Project prepare(boolean completed) {
        UserContext.set(new CurrentUser(completed ? 9L : 1L, completed ? "SYSTEM_ADMIN" : "USER"));
        Project p = new Project();
        p.setId(4L);
        p.setStatus(completed ? ProjectStatus.PENDING_ACCEPTANCE : ProjectStatus.IN_PROGRESS);
        when(projects.selectByIdForUpdate(4L)).thenReturn(p);
        when(projects.cancelIfActive(eq(4L), anyString(), any())).thenReturn(1);
        when(projects.reviewAcceptanceIfPending(eq(4L), any(ProjectStatus.class), any(), any())).thenReturn(1);
        ProjectAcceptance a = new ProjectAcceptance();
        a.setId(50L);
        a.setProjectId(4L);
        a.setSubmitterId(1L);
        a.setAcceptanceNo(1);
        a.setReviewStatus(ProjectAcceptanceStatus.PENDING);
        when(acceptances.selectById(50L)).thenReturn(a);
        when(
            acceptances.reviewIfPending(eq(50L), eq(4L), any(ProjectAcceptanceStatus.class), anyLong(), any(), any())
        ).thenReturn(1);
        return p;
    }

    void endProject(boolean completed) {
        if (completed) {
            ReviewProjectAcceptanceDTO dto = new ReviewProjectAcceptanceDTO();
            dto.setAction("APPROVE");
            context.getBean(ProjectAcceptanceService.class).reviewAcceptance(50L, dto);
        } else {
            CancelProjectDTO dto = new CancelProjectDTO();
            dto.setReason("取消项目");
            context.getBean(ProjectService.class).cancelProject(4L, dto);
        }
    }

    @Test
    void directClosureWithoutCallerTransactionIsRejectedBeforeMapperCalls() {
        var closure = context.getBean(ProjectRequestClosureService.class);
        assertThrows(IllegalTransactionStateException.class, () -> closure.closeForCompletedProject(4L, LocalDateTime.now()));
        assertThrows(IllegalTransactionStateException.class, () -> closure.closeForCancelledProject(4L, LocalDateTime.now()));
        verifyNoInteractions(invitations, leaves, transfers, tasks);
        assertEquals(0, tx.commits);
        assertEquals(0, tx.rollbacks);
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void projectEndAndAllClosuresShareOneTransactionAndOneTime(boolean completed) {
        Project p = prepare(completed);
        when(invitations.closePendingForProject(eq(4L), any(), anyString())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, tx.commits);
            return 0;
        });
        endProject(completed);
        LocalDateTime time = completed ? p.getActualEndTime() : p.getCancelledAt();
        String reason = completed ? "PROJECT_COMPLETED" : "PROJECT_CANCELLED";
        verify(invitations).closePendingForProject(eq(4L), same(time), eq(reason));
        verify(leaves).closePendingForProject(eq(4L), same(time), eq(reason));
        verify(transfers).closePendingForProject(eq(4L), same(time), eq(reason));
        if (!completed) verify(tasks).closePendingForProject(eq(4L), same(time), eq(reason));
        else verify(tasks, never()).closePendingForProject(anyLong(), any(), anyString());
        var order = inOrder(projects, acceptances, invitations);
        order.verify(projects).selectByIdForUpdate(4L);
        if (completed) {
            order.verify(projects).reviewAcceptanceIfPending(eq(4L), eq(ProjectStatus.COMPLETED), same(time), same(time));
            order
                .verify(acceptances)
                .reviewIfPending(eq(50L), eq(4L), eq(ProjectAcceptanceStatus.APPROVED), eq(9L), isNull(), same(time));
        } else order.verify(projects).cancelIfActive(eq(4L), anyString(), same(time));
        order.verify(invitations).closePendingForProject(4L, time, reason);
        assertEquals(1, tx.commits);
        assertEquals(0, tx.rollbacks);
        assertEquals(0, tx.suspensions);
    }

    @ParameterizedTest
    @CsvSource({ "true,0", "true,1", "true,2", "false,0", "false,1", "false,2", "false,3" })
    void closureFailureMarksWholeCallerTransactionForRollback(boolean completed, int step) {
        prepare(completed);
        RuntimeException error = new IllegalStateException("test closure update failure");
        switch (step) {
            case 0 -> when(invitations.closePendingForProject(anyLong(), any(), anyString())).thenThrow(error);
            case 1 -> when(leaves.closePendingForProject(anyLong(), any(), anyString())).thenThrow(error);
            case 2 -> when(transfers.closePendingForProject(anyLong(), any(), anyString())).thenThrow(error);
            case 3 -> when(tasks.closePendingForProject(anyLong(), any(), anyString())).thenThrow(error);
        }
        assertSame(error, assertThrows(RuntimeException.class, () -> endProject(completed)));
        assertEquals(0, tx.commits);
        assertEquals(1, tx.rollbacks);
        assertEquals(0, tx.suspensions);
        verifyNoInteractions(context.getBean(OperationLogService.class), context.getBean(NotificationService.class));
    }
}
