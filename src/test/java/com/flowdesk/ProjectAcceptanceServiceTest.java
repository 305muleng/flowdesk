package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.ReviewProjectAcceptanceDTO;
import com.flowdesk.dto.SubmitProjectAcceptanceDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.NotificationService;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectAcceptanceService;
import com.flowdesk.service.ProjectPermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

        TableInfoHelper.initTableInfo(
                assistant,
                User.class
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

    @Mock
    private UserMapper userMapper;

    @Mock
    private NotificationService notificationService;

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

    @Test
    void submittingAcceptanceNotifiesSystemAdmin() {

        Project project = new Project();
        project.setId(4L);
        project.setName("FlowDesk");
        project.setStatus("IN_PROGRESS");

        SubmitProjectAcceptanceDTO dto =
                new SubmitProjectAcceptanceDTO();

        dto.setSubmissionNote("项目开发完成，提交验收");

        // Tom：项目负责人
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskMapper.selectCount(any()))
                .thenReturn(0L);

        when(taskRequestMapper.selectCount(any()))
                .thenReturn(0L);

        when(projectAcceptanceMapper
                .selectMaxAcceptanceNo(4L))
                .thenReturn(null);

        // 模拟数据库生成验收记录 ID
        doAnswer(invocation -> {

            ProjectAcceptance acceptance =
                    invocation.getArgument(0);

            acceptance.setId(50L);

            return 1;

        }).when(projectAcceptanceMapper)
                .insert(any(ProjectAcceptance.class));

        // 系统管理员
        User admin = new User();
        admin.setId(9L);
        admin.setSystemRole("SYSTEM_ADMIN");
        admin.setStatus("ACTIVE");

        when(userMapper.selectList(any()))
                .thenReturn(List.of(admin));

        Long acceptanceId =
                projectAcceptanceService.submitAcceptance(
                        4L,
                        dto
                );

        assertEquals(50L, acceptanceId);

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notification =
                captor.getValue();

        assertEquals(
                9L,
                notification.getRecipientId()
        );

        assertEquals(
                1L,
                notification.getActorId()
        );

        assertEquals(
                "PROJECT_ACCEPTANCE_SUBMITTED",
                notification.getType()
        );

        assertEquals(
                "PROJECT_ACCEPTANCE",
                notification.getTargetType()
        );

        assertEquals(
                50L,
                notification.getTargetId()
        );
    }

    @Test
    void approvingAcceptanceNotifiesSubmitter() {

        ProjectAcceptance acceptance =
                new ProjectAcceptance();

        acceptance.setId(50L);
        acceptance.setProjectId(4L);
        acceptance.setSubmitterId(1L);
        acceptance.setAcceptanceNo(1);
        acceptance.setReviewStatus("PENDING");

        Project project = new Project();
        project.setId(4L);
        project.setName("FlowDesk");
        project.setStatus("PENDING_ACCEPTANCE");

        ReviewProjectAcceptanceDTO dto =
                new ReviewProjectAcceptanceDTO();

        dto.setAction("APPROVE");
        dto.setReviewNote("验收通过");

        // 系统管理员审核
        UserContext.set(
                new CurrentUser(9L, "SYSTEM_ADMIN")
        );

        when(projectAcceptanceMapper.selectById(50L))
                .thenReturn(acceptance);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        projectAcceptanceService.reviewAcceptance(
                50L,
                dto
        );

        // 原来的业务状态也顺便验证
        assertEquals(
                "APPROVED",
                acceptance.getReviewStatus()
        );

        assertEquals(
                "COMPLETED",
                project.getStatus()
        );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notification =
                captor.getValue();

        // Tom 收到通知
        assertEquals(
                1L,
                notification.getRecipientId()
        );

        // Admin 是操作人
        assertEquals(
                9L,
                notification.getActorId()
        );

        assertEquals(
                "PROJECT_ACCEPTANCE_APPROVED",
                notification.getType()
        );

        assertEquals(
                "PROJECT_ACCEPTANCE",
                notification.getTargetType()
        );

        assertEquals(
                50L,
                notification.getTargetId()
        );
    }

    @Test
    void rejectingAcceptanceNotifiesSubmitter() {

        ProjectAcceptance acceptance =
                new ProjectAcceptance();

        acceptance.setId(50L);
        acceptance.setProjectId(4L);
        acceptance.setSubmitterId(1L);
        acceptance.setAcceptanceNo(1);
        acceptance.setReviewStatus("PENDING");

        Project project = new Project();
        project.setId(4L);
        project.setName("FlowDesk");
        project.setStatus("PENDING_ACCEPTANCE");

        ReviewProjectAcceptanceDTO dto =
                new ReviewProjectAcceptanceDTO();

        dto.setAction("REJECT");
        dto.setReviewNote("还有部分功能需要调整");

        UserContext.set(
                new CurrentUser(9L, "SYSTEM_ADMIN")
        );

        when(projectAcceptanceMapper.selectById(50L))
                .thenReturn(acceptance);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        projectAcceptanceService.reviewAcceptance(
                50L,
                dto
        );

        assertEquals(
                "REJECTED",
                acceptance.getReviewStatus()
        );

        assertEquals(
                "IN_PROGRESS",
                project.getStatus()
        );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notification =
                captor.getValue();

        assertEquals(
                1L,
                notification.getRecipientId()
        );

        assertEquals(
                9L,
                notification.getActorId()
        );

        assertEquals(
                "PROJECT_ACCEPTANCE_REJECTED",
                notification.getType()
        );

        assertEquals(
                50L,
                notification.getTargetId()
        );
    }
}