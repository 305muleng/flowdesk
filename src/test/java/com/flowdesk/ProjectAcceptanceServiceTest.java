package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.SubmitProjectAcceptanceDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectAcceptanceMapper;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskRequestMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectAcceptance;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskRequest;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectAcceptanceService;
import com.flowdesk.service.ProjectPermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ProjectAcceptanceServiceTest {

    @BeforeAll
    static void initMybatisPlus() {

        MybatisConfiguration configuration =
                new MybatisConfiguration();

        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(
                        configuration,
                        ""
                );

        TableInfoHelper.initTableInfo(
                assistant,
                Task.class
        );

        TableInfoHelper.initTableInfo(
                assistant,
                TaskRequest.class
        );
    }

    @Mock
    private ProjectAcceptanceMapper projectAcceptanceMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private ProjectPermissionService projectPermissionService;

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private TaskRequestMapper taskRequestMapper;

    @InjectMocks
    private ProjectAcceptanceService projectAcceptanceService;

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void unfinishedTaskBlocksAcceptance() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        SubmitProjectAcceptanceDTO dto =
                new SubmitProjectAcceptanceDTO();

        dto.setSubmissionNote("提交验收");

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskMapper.selectCount(any()))
                .thenReturn(1L);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> projectAcceptanceService
                                .submitAcceptance(4L, dto)
                );

        assertEquals(
                409,
                exception.getCode()
        );

        assertEquals(
                "项目仍有未完成任务，不能提交验收",
                exception.getMessage()
        );
    }

    @Test
    void pendingTaskRequestBlocksAcceptance() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        SubmitProjectAcceptanceDTO dto =
                new SubmitProjectAcceptanceDTO();

        dto.setSubmissionNote("提交验收");

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 没有未完成任务
        when(taskMapper.selectCount(any()))
                .thenReturn(0L);

        // 但是还有一个 PENDING 的任务申请
        when(taskRequestMapper.selectCount(any()))
                .thenReturn(1L);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> projectAcceptanceService
                                .submitAcceptance(4L, dto)
                );

        assertEquals(
                "项目仍有待处理的任务申请，不能提交验收",
                exception.getMessage()
        );
    }

    @Test
    void validProjectCanSubmitAcceptance() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        SubmitProjectAcceptanceDTO dto =
                new SubmitProjectAcceptanceDTO();

        dto.setSubmissionNote(
                "Backend V1 已完成，提交验收"
        );

        // 模拟当前登录用户 Tom
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 没有未完成任务
        when(taskMapper.selectCount(any()))
                .thenReturn(0L);

        // 没有待处理任务申请
        when(taskRequestMapper.selectCount(any()))
                .thenReturn(0L);

        // 当前还没有验收记录
        // 所以这次应该是第 1 次验收
        when(projectAcceptanceMapper
                .selectMaxAcceptanceNo(4L))
                .thenReturn(null);

        projectAcceptanceService
                .submitAcceptance(
                        4L,
                        dto
                );

        // 项目应该进入待验收状态
        assertEquals(
                "PENDING_ACCEPTANCE",
                project.getStatus()
        );

        // 应该插入一条验收记录
        verify(projectAcceptanceMapper)
                .insert(any(ProjectAcceptance.class));

        // 应该更新项目状态
        verify(projectMapper)
                .updateById(project);

        // 应该记录审计日志
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
}