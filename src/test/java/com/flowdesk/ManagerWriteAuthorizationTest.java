package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.*;
import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.service.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagerWriteAuthorizationTest {
    @BeforeAll
    static void initializeMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "manager-write"), ProjectMember.class);
    }

    @AfterEach
    void clearContext() {
        UserContext.remove();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "remove", "invite", "reviewLeave", "reviewTaskRequest", "createTransfer"})
    void formerManagerCannotWriteAfterWaitingForProjectLock(String operation) {
        long projectId = 400L;
        UserContext.set(new CurrentUser(1L, "USER"));
        ProjectMapper projects = mock(ProjectMapper.class);
        ProjectMemberMapper members = mock(ProjectMemberMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TaskRequestMapper taskRequests = mock(TaskRequestMapper.class);
        MemberLeaveRequestMapper leaveRequests = mock(MemberLeaveRequestMapper.class);
        ProjectInvitationMapper invitations = mock(ProjectInvitationMapper.class);
        ProjectManagerTransferMapper transfers = mock(ProjectManagerTransferMapper.class);
        UserMapper users = mock(UserMapper.class);
        NotificationService notifications = mock(NotificationService.class);
        OperationLogService logs = mock(OperationLogService.class);
        ProjectTaskSummaryCache cache = mock(ProjectTaskSummaryCache.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ProjectPermissionService permissions = new ProjectPermissionService(members);

        ProjectMember actor = new ProjectMember();
        actor.setId(11L);
        actor.setProjectId(projectId);
        actor.setUserId(1L);
        actor.setStatus(ProjectMemberStatus.ACTIVE);
        actor.setRole("PROJECT_MANAGER");
        when(members.selectOne(any())).thenReturn(actor);
        when(members.selectMemberForUpdate(projectId, 1L)).thenReturn(actor);

        Project project = new Project();
        project.setId(projectId);
        project.setStatus(ProjectStatus.PREPARING);
        when(projects.selectByIdForUpdate(projectId)).thenAnswer(call -> {
            // 当前请求等待项目锁期间，保留旧负责人成员身份的 STAY 转让已提交。
            actor.setRole("DEVELOPER");
            return project;
        });

        ProjectService projectService = new ProjectService(projects, members, permissions, logs,
                tasks, taskRequests, leaveRequests, notifications, users, mock(ProjectRequestClosureService.class));
        ProjectInvitationService invitationService = new ProjectInvitationService(invitations, members,
                users, permissions, projects, notifications, logs, 7);
        MemberLeaveRequestService leaveService = new MemberLeaveRequestService(leaveRequests, projects,
                members, permissions, tasks, notifications, logs, taskRequests, 7);
        TaskRequestService requestService = new TaskRequestService(taskRequests, projects, permissions,
                tasks, members, notifications, users, logs, cache, mock(com.flowdesk.service.TaskReminderService.class));
        ProjectManagerTransferService transferService = new ProjectManagerTransferService(transfers,
                projects, users, tasks, permissions, notifications, logs, encoder, members, taskRequests, leaveRequests, 7);

        Runnable action = switch (operation) {
            case "start" -> () -> projectService.startProject(projectId);
            case "remove" -> () -> projectService.removeProjectMember(projectId, 3L, new RemoveProjectMemberDTO());
            case "invite" -> () -> invitationService.sendInvitation(projectId, new SendInvitationDTO());
            case "reviewLeave" -> () -> leaveService.reviewLeaveRequest(projectId, 30L, new ReviewMemberLeaveRequestDTO());
            case "reviewTaskRequest" -> () -> requestService.reviewTaskRequest(projectId, 30L, new ReviewTaskRequestDTO());
            case "createTransfer" -> () -> transferService.createTransfer(projectId, new CreateProjectManagerTransferDTO());
            default -> throw new IllegalArgumentException(operation);
        };

        BusinessException error = assertThrows(BusinessException.class, action::run);
        assertEquals(403, error.getCode());
        var order = inOrder(members, projects);
        order.verify(members).selectOne(any());
        order.verify(projects).selectByIdForUpdate(projectId);
        order.verify(members).selectMemberForUpdate(projectId, 1L);
        verifyNoMoreInteractions(projects);
        verifyNoInteractions(tasks, taskRequests, leaveRequests, invitations, transfers,
                users, notifications, logs, cache, encoder);
    }
}
