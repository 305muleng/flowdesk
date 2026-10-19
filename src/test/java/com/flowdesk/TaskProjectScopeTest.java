package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.*;
import com.flowdesk.dto.HandoverTaskDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskProjectScopeTest {

    TaskMapper tasks = mock(TaskMapper.class);
    ProjectMapper projects = mock(ProjectMapper.class);
    ProjectMemberMapper members = mock(ProjectMemberMapper.class);
    TaskService service = new TaskService(
        tasks,
        projects,
        members,
        new ProjectPermissionService(members),
        mock(TaskSubmissionMapper.class),
        mock(TaskCommentMapper.class),
        mock(OperationLogService.class),
        mock(NotificationService.class),
        mock(UserMapper.class),
        mock(ProjectTaskSummaryCache.class)
    );

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "scope-test"), ProjectMember.class);
        UserContext.set(new CurrentUser(1L, "USER"));
    }

    @AfterEach
    void cleanup() {
        UserContext.remove();
    }

    Task task() {
        Task t = new Task();
        t.setId(10L);
        t.setProjectId(4L);
        return t;
    }

    @Test
    void nonMemberCannotQueryProjectTasksOrTaskDetailsOrHandover() {
        assertEquals(
            403,
            assertThrows(BusinessException.class, () -> service.getProjectTasks(4L, "ALL", "ALL", 1, 20, null, false)).getCode()
        );
        when(tasks.selectById(10L)).thenReturn(task());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.getTaskDetail(10L)).getCode());
        assertEquals(
            403,
            assertThrows(BusinessException.class, () ->
                service.handoverDisabledAssignee(10L, new HandoverTaskDTO(2L, 3L, "交接"))
            ).getCode()
        );
        verify(tasks, never()).selectProjectTasks(any(), anyLong(), anyString(), anyLong(), anyString(), any(), anyBoolean());
        verify(tasks, never()).selectTaskDetail(anyLong());
        verify(tasks, never()).handoverDisabledAssigneeIfCurrent(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(projects);
    }

    @Test
    void systemAdminCannotBypassProjectMembership() {
        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));
        assertEquals(
            403,
            assertThrows(BusinessException.class, () -> service.getProjectTasks(4L, "ALL", "ALL", 1, 20, null, false)).getCode()
        );
        verifyNoInteractions(tasks, projects, members);
    }
}
