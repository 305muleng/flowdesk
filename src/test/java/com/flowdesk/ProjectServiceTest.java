package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CancelProjectDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.model.Project;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectPermissionService;
import com.flowdesk.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @AfterEach
    void cleanUp() {
        UserContext.remove();
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
                .updateById(project);

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
                .updateById(project);

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
                .updateById(project);
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
}