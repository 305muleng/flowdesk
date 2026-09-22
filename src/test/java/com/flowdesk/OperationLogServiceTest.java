package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.OperationLogMapper;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.model.OperationLog;
import com.flowdesk.model.Project;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationLogServiceTest {

    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private ProjectPermissionService projectPermissionService;
    @Mock
    private ProjectMapper projectMapper;

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void nonMemberCannotReadProjectLogs() {
        OperationLogService service = service();
        doThrow(new BusinessException(403, "你不是该项目的有效成员"))
                .when(projectPermissionService).requireProjectMember(4L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.getProjectLogs(4L, 50));

        assertEquals(403, exception.getCode());
        verify(operationLogMapper, never()).selectProjectLogs(any(), anyInt());
    }

    @Test
    void deletedProjectLogsAreNotReadable() {
        OperationLogService service = service();
        Project project = new Project();
        project.setId(4L);
        project.setDeletedAt(java.time.LocalDateTime.now());
        when(projectMapper.selectById(4L)).thenReturn(project);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.getProjectLogs(4L, 50));

        assertEquals(404, exception.getCode());
        verify(operationLogMapper, never()).selectProjectLogs(any(), anyInt());
    }

    @Test
    void actorAlwaysComesFromUserContext() {
        OperationLogService service = service();
        UserContext.set(new CurrentUser(7L, "USER"));

        service.record(4L, 999L, "PROJECT", 4L, "TEST", "测试", null, null);

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getActorId());
        assertEquals(4L, captor.getValue().getProjectId());
    }

    private OperationLogService service() {
        return new OperationLogService(
                operationLogMapper, new ObjectMapper(), projectPermissionService, projectMapper);
    }
}
