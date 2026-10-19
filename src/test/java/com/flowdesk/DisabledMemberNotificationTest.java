package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import com.flowdesk.vo.ProjectMemberVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DisabledMemberNotificationTest {

    AnnotationConfigApplicationContext context;
    UserMapper users;
    ProjectMemberMapper members;
    ProjectMapper projects;
    TaskMapper tasks;
    NotificationService notifications;
    AdminUserService admin;
    StubTransactionManager transactions;

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        UserMapper users() {
            return mock(UserMapper.class);
        }

        @Bean
        ProjectMemberMapper members() {
            return mock(ProjectMemberMapper.class);
        }

        @Bean
        ProjectMapper projects() {
            return mock(ProjectMapper.class);
        }

        @Bean
        TaskMapper tasks() {
            return mock(TaskMapper.class);
        }

        @Bean
        NotificationService notifications() {
            return mock(NotificationService.class);
        }

        @Bean
        StubTransactionManager transactionManager() {
            return new StubTransactionManager();
        }

        @Bean
        AdminUserService admin(UserMapper u, ProjectMemberMapper m, org.springframework.context.ApplicationEventPublisher e) {
            return new AdminUserService(u, m, e);
        }

        @Bean
        DisabledMemberNotificationService listener(
            ProjectMemberMapper m,
            ProjectMapper p,
            TaskMapper t,
            NotificationService n,
            StubTransactionManager tx
        ) {
            return new DisabledMemberNotificationService(m, p, t, n, tx);
        }
    }

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Task.class);
        context = new AnnotationConfigApplicationContext(Config.class);
        users = context.getBean(UserMapper.class);
        members = context.getBean(ProjectMemberMapper.class);
        projects = context.getBean(ProjectMapper.class);
        tasks = context.getBean(TaskMapper.class);
        notifications = context.getBean(NotificationService.class);
        admin = context.getBean(AdminUserService.class);
        transactions = context.getBean(StubTransactionManager.class);
        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));
        User user = new User();
        user.setId(2L);
        user.setRealName("测试成员");
        user.setStatus("ACTIVE");
        when(users.selectByIdForUpdate(2L)).thenReturn(user);
    }

    @AfterEach
    void cleanup() {
        context.close();
        UserContext.remove();
    }

    Project project(long id, String status) {
        Project p = new Project();
        p.setId(id);
        p.setName("测试项目" + id);
        p.setStatus(status);
        return p;
    }

    void projectReady(long id, String status) {
        when(projects.selectByIdForUpdate(id)).thenReturn(project(id, status));
        ProjectMember membership = new ProjectMember();
        membership.setStatus("ACTIVE");
        when(members.selectMemberForUpdate(id, 2L)).thenReturn(membership);
        ProjectMemberVO manager = new ProjectMemberVO();
        manager.setUserId(id + 100);
        when(members.selectActiveProjectManager(id)).thenReturn(manager);
    }

    @Test
    void sendsOncePerProjectAfterCommitIncludingZeroTasksAndFrozenProject() {
        when(members.selectNotificationProjectIds(2L)).thenAnswer(call -> {
            assertEquals(1, transactions.commits, "必须先提交账号禁用");
            return List.of(4L, 5L, 6L);
        });
        projectReady(4, "PREPARING");
        projectReady(5, "IN_PROGRESS");
        projectReady(6, "PENDING_ACCEPTANCE");
        when(tasks.selectCount(any())).thenReturn(0L, 2L, 0L);
        admin.disableUser(2L);

        var captor = ArgumentCaptor.forClass(CreateNotificationDTO.class);
        verify(notifications, times(3)).createNotification(captor.capture());
        assertEquals(List.of(4L, 5L, 6L), captor.getAllValues().stream().map(CreateNotificationDTO::getProjectId).toList());
        assertEquals(
            List.of(104L, 105L, 106L),
            captor.getAllValues().stream().map(CreateNotificationDTO::getRecipientId).toList()
        );
        assertTrue(captor.getAllValues().get(0).getContent().contains("无待交接任务"));
        assertTrue(captor.getAllValues().get(1).getContent().contains("2 项未完成任务"));
        assertTrue(captor.getAllValues().get(2).getContent().contains("继续冻结"));
        assertEquals(5, transactions.commits); // 禁用、项目查询、三条通知
        assertEquals(4, transactions.suspensions);
        assertEquals(0, transactions.rollbacks);
        admin.disableUser(2L);
        verify(notifications, times(3)).createNotification(any());
        verify(users, times(1)).updateById(any(User.class));
    }

    @Test
    void notificationFailureRollsBackOnlyThatProjectAndContinues() {
        when(members.selectNotificationProjectIds(2L)).thenReturn(List.of(4L, 5L));
        projectReady(4, "IN_PROGRESS");
        projectReady(5, "IN_PROGRESS");
        doThrow(new IllegalStateException("test notification failure")).doNothing().when(notifications).createNotification(any());
        assertDoesNotThrow(() -> admin.disableUser(2L));
        assertEquals("DISABLED", users.selectByIdForUpdate(2L).getStatus());
        verify(notifications, times(2)).createNotification(any());
        assertEquals(3, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void disablingRollbackDoesNotSendNotifications() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            admin.disableUser(2L);
            status.setRollbackOnly();
        });
        verifyNoInteractions(notifications);
        verify(members, never()).selectNotificationProjectIds(anyLong());
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void queryFailureDoesNotUndoDisable() {
        when(members.selectNotificationProjectIds(2L)).thenThrow(new IllegalStateException("test query failure"));
        assertDoesNotThrow(() -> admin.disableUser(2L));
        assertEquals(1, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verifyNoInteractions(notifications);
    }

    @Test
    void terminalProjectsOrDepartedMemberAreNotNotified() {
        when(members.selectNotificationProjectIds(2L)).thenReturn(List.of(4L, 5L, 6L, 7L));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project(4, "COMPLETED"));
        when(projects.selectByIdForUpdate(5L)).thenReturn(project(5, "ARCHIVED"));
        when(projects.selectByIdForUpdate(6L)).thenReturn(project(6, "CANCELLED"));
        when(projects.selectByIdForUpdate(7L)).thenReturn(project(7, "IN_PROGRESS"));
        admin.disableUser(2L);
        verifyNoInteractions(notifications, tasks);
    }

    @Test
    void blockingProjectsExplainEachRequiredAction() {
        when(members.selectManagedProjectsBlockingDisable(2L)).thenReturn(
            List.of(project(4, "PREPARING"), project(5, "IN_PROGRESS"), project(6, "PENDING_ACCEPTANCE"), project(7, "COMPLETED"))
        );
        var error = assertThrows(BusinessException.class, () -> admin.disableUser(2L));
        assertEquals(409, error.getCode());
        for (long id : List.of(4L, 5L, 6L, 7L)) assertTrue(error.getMessage().contains("项目 #" + id));
        assertTrue(error.getMessage().contains("先转让项目负责人"));
        assertTrue(error.getMessage().contains("先完成验收处理，当前不能转让"));
        assertTrue(error.getMessage().contains("先归档项目"));
        verify(users, never()).updateById(any(User.class));
        verifyNoInteractions(notifications);
    }

    @Test
    void nonAdminCannotQueryBlockingProjectsOrDisableUser() {
        UserContext.set(new CurrentUser(9L, "USER"));
        assertEquals(403, assertThrows(BusinessException.class, () -> admin.disableUser(2L)).getCode());
        verifyNoInteractions(users, members, notifications);
    }
}
