package com.flowdesk;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.AddTaskCommentDTO;
import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskComment;
import com.flowdesk.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCommentAuthorizationTest {
    private TaskMapper tasks;
    private ProjectMapper projects;
    private ProjectMemberMapper members;
    private TaskCommentMapper comments;
    private TaskService service;
    private Project project;
    private ProjectMember currentMembership;

    @BeforeEach
    void setup() {
        tasks = mock(TaskMapper.class);
        projects = mock(ProjectMapper.class);
        members = mock(ProjectMemberMapper.class);
        comments = mock(TaskCommentMapper.class);
        service = new TaskService(tasks, projects, members, new ProjectPermissionService(members),
                mock(TaskSubmissionMapper.class), comments, mock(OperationLogService.class),
                mock(NotificationService.class), mock(UserMapper.class), mock(ProjectTaskSummaryCache.class));
        UserContext.set(new CurrentUser(2L, "USER"));
        Task task = new Task();
        task.setId(10L);
        task.setProjectId(4L);
        when(tasks.selectById(10L)).thenReturn(task);
        project = new Project();
        project.setId(4L);
        project.setStatus(ProjectStatus.IN_PROGRESS);
        currentMembership = membership("DEVELOPER", ProjectMemberStatus.ACTIVE);
        when(members.selectOne(any())).thenReturn(currentMembership);
        when(members.selectMemberForUpdate(4L, 2L)).thenAnswer(invocation -> currentMembership);
        when(projects.selectByIdForUpdate(4L)).thenReturn(project);
    }

    @AfterEach
    void cleanup() {
        UserContext.remove();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INACTIVE"})
    void membershipRevokedWhileWaitingForProjectLockCannotWriteComment(String state) {
        // 模拟初检后等待项目锁，等待期间移除成员的事务已提交。
        when(projects.selectByIdForUpdate(4L)).thenAnswer(invocation -> {
            currentMembership = state.equals("MISSING") ? null : membership("DEVELOPER", ProjectMemberStatus.INACTIVE);
            return project;
        });

        BusinessException error = assertThrows(BusinessException.class, () -> service.addTaskComment(10L, comment()));
        assertEquals(403, error.getCode());
        verifyNoInteractions(comments);
        var order = inOrder(members, projects);
        order.verify(members).selectOne(any());
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEVELOPER", "PROJECT_MANAGER"})
    void activeMembersCanStillCommentAfterLockingMembership(String role) {
        currentMembership = membership(role, ProjectMemberStatus.ACTIVE);
        when(comments.insert(any(TaskComment.class))).thenAnswer(invocation -> {
            TaskComment saved = invocation.getArgument(0);
            saved.setId(20L);
            assertEquals(2L, saved.getAuthorId());
            assertEquals("项目讨论", saved.getContent());
            return 1;
        });
        assertEquals(20L, service.addTaskComment(10L, comment()));
        var order = inOrder(projects, members, comments);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 2L);
        order.verify(comments).insert(any(TaskComment.class));
    }

    @Test
    void systemAdminCannotCommentEvenWithHistoricalMembership() {
        UserContext.set(new CurrentUser(2L, "SYSTEM_ADMIN"));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.addTaskComment(10L, comment())).getCode());
        verifyNoInteractions(projects, members, comments);
    }

    private static AddTaskCommentDTO comment() {
        AddTaskCommentDTO dto = new AddTaskCommentDTO();
        dto.setContent(" 项目讨论 ");
        return dto;
    }

    private static ProjectMember membership(String role, ProjectMemberStatus status) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(4L);
        member.setUserId(2L);
        member.setRole(role);
        member.setStatus(status);
        return member;
    }
}
