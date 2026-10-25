package com.flowdesk;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.UpdateProjectDTO;
import com.flowdesk.dto.UpdateTaskDTO;
import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.enums.TaskStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DetailsEditingServiceTest {
    ProjectMapper projects;
    TaskMapper tasks;
    ProjectMemberMapper members;
    OperationLogService logs;
    ProjectTaskSummaryCache cache;
    ProjectService projectService;
    TaskService taskService;
    TaskReminderService reminders = mock(TaskReminderService.class);
    Project project;
    Task currentTask;
    ProjectMember manager;

    @BeforeEach
    void setup() {
        projects = mock(ProjectMapper.class);
        tasks = mock(TaskMapper.class);
        members = mock(ProjectMemberMapper.class);
        logs = mock(OperationLogService.class);
        cache = mock(ProjectTaskSummaryCache.class);
        var permissions = new ProjectPermissionService(members);
        projectService = new ProjectService(projects, members, permissions, logs, tasks, mock(TaskRequestMapper.class),
                mock(MemberLeaveRequestMapper.class), mock(NotificationService.class), mock(UserMapper.class),
                mock(ProjectRequestClosureService.class));
        taskService = new TaskService(tasks, projects, members, permissions, mock(TaskSubmissionMapper.class),
                mock(TaskCommentMapper.class), logs, mock(NotificationService.class), mock(UserMapper.class), cache, reminders);
        UserContext.set(new CurrentUser(1L, "USER"));
        project = new Project();
        project.setId(4L);
        project.setName("原项目");
        project.setDescription("原描述");
        project.setStatus(ProjectStatus.PREPARING);
        project.setRepositoryUrl("https://example.com/repo");
        when(projects.selectByIdForUpdate(4L)).thenReturn(project);
        when(projects.updateDetailsIfEditable(any())).thenReturn(1);
        Task original = new Task();
        original.setId(10L);
        original.setProjectId(4L);
        original.setStatus(TaskStatus.TODO);
        currentTask = new Task();
        currentTask.setId(10L);
        currentTask.setProjectId(4L);
        currentTask.setTitle("旧任务");
        currentTask.setGoal("旧目标");
        currentTask.setStatus(TaskStatus.TODO);
        currentTask.setAssigneeId(2L);
        currentTask.setPriority("LOW");
        when(tasks.selectById(10L)).thenReturn(original);
        when(tasks.selectByIdForUpdate(10L)).thenReturn(currentTask);
        when(tasks.updateDetailsIfTodo(any())).thenReturn(1);
        manager = new ProjectMember();
        manager.setRole("PROJECT_MANAGER");
        manager.setStatus(ProjectMemberStatus.ACTIVE);
        when(members.selectMemberForUpdate(4L, 1L)).thenAnswer(call -> manager);
    }

    @AfterEach
    void cleanup() { UserContext.remove(); }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARING", "IN_PROGRESS"})
    void projectEditPreservesLifecycleLinksAndAuditsNullableFields(String status) {
        project.setStatus(ProjectStatus.fromCode(status));
        projectService.updateProjectDetails(4L, projectDto());
        assertEquals("新项目", project.getName());
        assertNull(project.getDescription());
        assertNull(project.getExpectedEndTime());
        assertEquals(ProjectStatus.fromCode(status), project.getStatus());
        assertEquals("https://example.com/repo", project.getRepositoryUrl());
        var before = ArgumentCaptor.forClass(Map.class);
        var after = ArgumentCaptor.forClass(Map.class);
        var order = inOrder(projects, members, logs);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 1L);
        order.verify(projects).updateDetailsIfEditable(same(project));
        order.verify(logs).record(eq(4L), eq("PROJECT"), eq(4L), eq("UPDATE_PROJECT_DETAILS"), anyString(), before.capture(), after.capture());
        assertEquals("原描述", before.getValue().get("description"));
        assertTrue(before.getValue().containsKey("goal"));
        assertNull(before.getValue().get("goal"));
        assertEquals("新目标", after.getValue().get("goal"));
        assertNull(after.getValue().get("description"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARING", "IN_PROGRESS"})
    void taskEditUsesCurrentTaskAndKeepsAssigneeAndStatus(String status) {
        project.setStatus(ProjectStatus.fromCode(status));
        taskService.updateTaskDetails(10L, taskDto());
        verify(reminders).syncTask(10L);
        assertEquals("新任务", currentTask.getTitle());
        assertEquals("HIGH", currentTask.getPriority());
        assertEquals(2L, currentTask.getAssigneeId());
        assertEquals(TaskStatus.TODO, currentTask.getStatus());
        var before = ArgumentCaptor.forClass(Map.class);
        var after = ArgumentCaptor.forClass(Map.class);
        var order = inOrder(projects, members, tasks, logs);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 1L);
        order.verify(tasks).selectByIdForUpdate(10L);
        order.verify(tasks).updateDetailsIfTodo(same(currentTask));
        order.verify(logs).record(eq(4L), eq("TASK"), eq(10L), eq("UPDATE_TASK_DETAILS"), anyString(), before.capture(), after.capture());
        assertEquals("旧任务", before.getValue().get("title"));
        assertEquals("新目标", after.getValue().get("goal"));
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_ACCEPTANCE", "COMPLETED", "ARCHIVED", "CANCELLED"})
    void frozenProjectsRejectBothEditingRoutes(String status) {
        project.setStatus(ProjectStatus.fromCode(status));
        assertCode(409, () -> projectService.updateProjectDetails(4L, projectDto()));
        assertCode(409, () -> taskService.updateTaskDetails(10L, taskDto()));
        verify(projects, never()).updateDetailsIfEditable(any());
        verify(tasks, never()).updateDetailsIfTodo(any());
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "REVIEW", "DONE", "CANCELLED"})
    void taskThatStartedWhileWaitingForTheProjectLockCannotBeEdited(String status) {
        // 初次读取仍是 TODO；锁后读取必须以最新任务状态为准。
        currentTask.setStatus(TaskStatus.fromCode(status));
        assertCode(409, () -> taskService.updateTaskDetails(10L, taskDto()));
        verify(tasks, never()).updateDetailsIfTodo(any());
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEVELOPER", "INACTIVE", "MISSING"})
    void changedMembershipAfterWaitingCannotUseOldManagerAuthority(String state) {
        when(projects.selectByIdForUpdate(4L)).thenAnswer(call -> {
            if (state.equals("MISSING")) manager = null;
            else if (state.equals("INACTIVE")) manager.setStatus(ProjectMemberStatus.INACTIVE);
            else manager.setRole("DEVELOPER");
            return project;
        });
        assertCode(403, () -> projectService.updateProjectDetails(4L, projectDto()));
        assertCode(403, () -> taskService.updateTaskDetails(10L, taskDto()));
        verify(projects, never()).updateDetailsIfEditable(any());
        verify(tasks, never()).updateDetailsIfTodo(any());
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "OTHER_PROJECT_MANAGER"})
    void systemAdminAndOtherProjectManagersCannotEdit(String role) {
        if (role.equals("SYSTEM_ADMIN")) UserContext.set(new CurrentUser(1L, role));
        else manager = null;
        assertCode(403, () -> projectService.updateProjectDetails(4L, projectDto()));
        assertCode(403, () -> taskService.updateTaskDetails(10L, taskDto()));
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROJECT", "TASK"})
    void unsuccessfulConditionalUpdateDoesNotWriteSuccessAudit(String kind) {
        when(projects.updateDetailsIfEditable(any())).thenReturn(0);
        when(tasks.updateDetailsIfTodo(any())).thenReturn(0);
        assertCode(409, () -> edit(kind));
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEADLINE", "PRIORITY"})
    void taskCreationValidationStillAppliesToEdits(String invalid) {
        var dto = taskDto();
        if (invalid.equals("DEADLINE")) dto.setDeadline(LocalDateTime.now().minusDays(1));
        else dto.setPriority("URGENT");
        assertCode(400, () -> taskService.updateTaskDetails(10L, dto));
        verify(tasks, never()).updateDetailsIfTodo(any());
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROJECT", "TASK"})
    void auditFailureTriggersRollbackThroughTheSpringTransactionProxy(String kind) {
        // 事务替身只证明 Spring 事务边界，不等同于真实数据库回滚验证。
        var tx = new StubTransactionManager();
        Object target = kind.equals("PROJECT") ? projectService : taskService;
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        doThrow(new BusinessException(500, "审计失败")).when(logs)
                .record(anyLong(), anyString(), anyLong(), anyString(), anyString(), any(), any());
        if (kind.equals("PROJECT")) {
            var proxy = (ProjectService) factory.getProxy();
            assertCode(500, () -> proxy.updateProjectDetails(4L, projectDto()));
        } else {
            var proxy = (TaskService) factory.getProxy();
            assertCode(500, () -> proxy.updateTaskDetails(10L, taskDto()));
        }
        assertEquals(1, tx.rollbacks);
        assertEquals(0, tx.commits);
    }

    private void edit(String kind) {
        if (kind.equals("PROJECT")) projectService.updateProjectDetails(4L, projectDto());
        else taskService.updateTaskDetails(10L, taskDto());
    }

    private static void assertCode(int code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(BusinessException.class, operation).getCode());
    }

    private static UpdateProjectDTO projectDto() {
        var dto = new UpdateProjectDTO();
        dto.setName(" 新项目 ");
        dto.setGoal("新目标");
        return dto;
    }

    private static UpdateTaskDTO taskDto() {
        var dto = new UpdateTaskDTO();
        dto.setTitle(" 新任务 ");
        dto.setDescription("新描述");
        dto.setGoal("新目标");
        dto.setPriority(" high ");
        dto.setDeadline(LocalDateTime.now().plusDays(2));
        return dto;
    }
}
