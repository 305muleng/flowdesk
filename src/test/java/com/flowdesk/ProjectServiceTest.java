package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CancelProjectDTO;
import com.flowdesk.dto.CreateProjectDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectPermissionService;
import com.flowdesk.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class ProjectServiceTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private ProjectPermissionService projectPermissionService;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private ProjectService projectService;

    @BeforeEach
    void setUpAtomicTransitions() {
        lenient().when(projectMapper.startIfPreparing(anyLong(), any())).thenReturn(1);
        lenient().when(projectMapper.cancelIfActive(anyLong(), any(), any())).thenReturn(1);
        lenient().when(projectMapper.archiveIfCompleted(anyLong(), any())).thenReturn(1);
    }

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void systemAdminCannotCreateProject() {

        UserContext.set(
                new CurrentUser(1L, "SYSTEM_ADMIN")
        );

        CreateProjectDTO dto = new CreateProjectDTO();
        dto.setName("FlowDesk");

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> projectService.createProject(dto)
                );

        assertEquals(403, exception.getCode());
        assertEquals("系统管理员不能创建项目", exception.getMessage());

        verify(projectMapper, never()).insert(any(Project.class));
        verify(projectMemberMapper, never())
                .insert(any(ProjectMember.class));
    }

    @Test
    void normalUserCreatesProjectAndBecomesManager() {
        UserContext.set(new CurrentUser(2L, "USER"));
        CreateProjectDTO dto = new CreateProjectDTO();
        dto.setName(" FlowDesk ");

        doAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(4L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));

        Long projectId = projectService.createProject(dto);

        assertEquals(4L, projectId);
        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(captor.capture());
        ProjectMember member = captor.getValue();
        assertEquals(4L, member.getProjectId());
        assertEquals(2L, member.getUserId());
        assertEquals("PROJECT_MANAGER", member.getRole());
        assertEquals("ACTIVE", member.getStatus());
        verify(operationLogService).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void memberInsertFailurePropagatesInsideTransactionalCreate() throws Exception {
        UserContext.set(new CurrentUser(2L, "USER"));
        CreateProjectDTO dto = new CreateProjectDTO();
        dto.setName("FlowDesk");
        doAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(4L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        doThrow(new RuntimeException("member insert failed"))
                .when(projectMemberMapper).insert(any(ProjectMember.class));

        assertThrows(RuntimeException.class, () -> projectService.createProject(dto));
        verify(operationLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
        assertNotNull(ProjectService.class
                .getMethod("createProject", CreateProjectDTO.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test
    void systemAdminProjectListIsAlwaysEmpty() {
        UserContext.set(new CurrentUser(9L, "SYSTEM_ADMIN"));

        assertTrue(projectService.getMyProjects().isEmpty());
        verify(projectMapper, never()).selectProjectsForUser(any());
    }

    @Test
    void preparingProjectCanBeStarted() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("PREPARING");

        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        projectService.startProject(4L);

        assertEquals(
                "IN_PROGRESS",
                project.getStatus()
        );

        assertNotNull(
                project.getStartTime()
        );

        verify(projectMapper)
                .startIfPreparing(eq(4L), any());

        verify(operationLogService)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void completedProjectCanBeArchived() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("COMPLETED");

        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        projectService.archiveProject(4L);

        assertEquals(
                "ARCHIVED",
                project.getStatus()
        );

        verify(projectMapper)
                .archiveIfCompleted(eq(4L), any());

        verify(operationLogService)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void inProgressProjectCanBeCancelled() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        CancelProjectDTO dto = new CancelProjectDTO();
        dto.setReason("测试取消项目");

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        projectService.cancelProject(4L, dto);

        assertEquals(
                "CANCELLED",
                project.getStatus()
        );

        assertEquals(
                "测试取消项目",
                project.getCancelReason()
        );

        assertNotNull(
                project.getCancelledAt()
        );

        verify(projectMapper)
                .cancelIfActive(eq(4L), any(), any());
    }

    @Test
    void nonCompletedProjectCannotBeArchived() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> projectService.archiveProject(4L)
                );

        assertEquals(
                "只有已完成的项目可以归档",
                exception.getMessage()
        );
    }
    @Test
    void concurrentProjectTransitionDoesNotWriteMisleadingLog() {
        Project project = new Project();
        project.setId(4L);
        project.setStatus("COMPLETED");
        UserContext.set(new CurrentUser(1L, "USER"));
        when(projectMapper.selectById(4L)).thenReturn(project);
        when(projectMapper.archiveIfCompleted(eq(4L), any())).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> projectService.archiveProject(4L));

        assertEquals(409, exception.getCode());
        verify(operationLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
