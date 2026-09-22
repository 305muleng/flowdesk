package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.SendInvitationDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectInvitationMapper;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectInvitation;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.User;
import com.flowdesk.service.NotificationService;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectInvitationService;
import com.flowdesk.service.ProjectPermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class ProjectInvitationServiceTest {

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
                User.class
        );

        TableInfoHelper.initTableInfo(
                assistant,
                ProjectMember.class
        );

        TableInfoHelper.initTableInfo(
                assistant,
                ProjectInvitation.class
        );
    }

    @Mock
    private ProjectInvitationMapper projectInvitationMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ProjectPermissionService projectPermissionService;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private OperationLogService operationLogService;

    private ProjectInvitationService projectInvitationService;

    @BeforeEach
    void setUp() {

        projectInvitationService =
                new ProjectInvitationService(
                        projectInvitationMapper,
                        projectMemberMapper,
                        userMapper,
                        projectPermissionService,
                        projectMapper,
                        notificationService,
                        operationLogService,
                        7
                );

        lenient().when(projectInvitationMapper.transitionPending(
                anyLong(), anyString(), any(LocalDateTime.class)
        )).thenReturn(1);
    }

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void systemAdminCannotBeInvited() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        User admin = new User();
        admin.setId(2L);
        admin.setUsername("admin");
        admin.setSystemRole("SYSTEM_ADMIN");
        admin.setStatus("ACTIVE");

        SendInvitationDTO dto = new SendInvitationDTO();
        dto.setUsername("admin");

        UserContext.set(new CurrentUser(1L, "USER"));

        when(projectMapper.selectByIdForUpdate(4L)).thenReturn(project);
        when(userMapper.selectOne(any())).thenReturn(admin);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> projectInvitationService.sendInvitation(4L, dto)
                );

        assertEquals(403, exception.getCode());
        assertEquals("不能邀请系统管理员加入项目", exception.getMessage());

        verify(projectInvitationMapper, never())
                .insert(any(ProjectInvitation.class));
        verify(projectMemberMapper, never())
                .insert(any(ProjectMember.class));
        verify(notificationService, never())
                .createNotification(any(CreateNotificationDTO.class));
    }

    @Test
    void systemAdminCannotAcceptExistingInvitation() {

        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setId(50L);
        invitation.setProjectId(4L);
        invitation.setInviterId(1L);
        invitation.setInviteeId(2L);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));

        User admin = new User();
        admin.setId(2L);
        admin.setSystemRole("SYSTEM_ADMIN");
        admin.setStatus("ACTIVE");

        // 即使登录态仍保留旧角色，也必须以数据库当前角色为准。
        UserContext.set(new CurrentUser(2L, "USER"));

        when(projectInvitationMapper.selectById(50L))
                .thenReturn(invitation);
        when(userMapper.selectById(2L)).thenReturn(admin);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> projectInvitationService.acceptInvitation(50L)
                );

        assertEquals(403, exception.getCode());
        assertEquals("系统管理员不能加入项目", exception.getMessage());
        assertEquals("PENDING", invitation.getStatus());

        verify(projectMemberMapper, never())
                .insert(any(ProjectMember.class));
        verify(projectMemberMapper, never())
                .updateById(any(ProjectMember.class));
        verify(projectInvitationMapper, never())
                .updateById(any(ProjectInvitation.class));
        verify(notificationService, never())
                .createNotification(any(CreateNotificationDTO.class));
    }

    /**
     * Tom 邀请 Jack
     * → Jack 应该收到项目邀请通知
     */
    @Test
    void sendingInvitationNotifiesInvitee() {

        Project project = new Project();
        project.setId(4L);
        project.setName("FlowDesk");
        project.setStatus("IN_PROGRESS");

        User jack = new User();
        jack.setId(2L);
        jack.setUsername("jack");
        jack.setStatus("ACTIVE");

        SendInvitationDTO dto =
                new SendInvitationDTO();

        dto.setUsername("jack");

        // 当前登录用户：Tom
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectByIdForUpdate(4L))
                .thenReturn(project);

        // 根据用户名找到 Jack
        when(userMapper.selectOne(any()))
                .thenReturn(jack);

        // Jack 目前还不是项目成员
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(null);

        // 没有重复的有效邀请
        when(projectInvitationMapper.selectCount(any()))
                .thenReturn(0L);

        // 模拟数据库插入后生成 invitationId = 50
        doAnswer(invocation -> {

            ProjectInvitation invitation =
                    invocation.getArgument(0);

            invitation.setId(50L);

            return 1;

        }).when(projectInvitationMapper)
                .insert(any(ProjectInvitation.class));

        Long invitationId =
                projectInvitationService.sendInvitation(
                        4L,
                        dto
                );

        assertEquals(
                50L,
                invitationId
        );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(
                        captor.capture()
                );

        CreateNotificationDTO notification =
                captor.getValue();

        // 收件人：Jack
        assertEquals(
                2L,
                notification.getRecipientId()
        );

        // 操作人：Tom
        assertEquals(
                1L,
                notification.getActorId()
        );

        assertEquals(
                "PROJECT_INVITATION",
                notification.getType()
        );

        assertEquals(
                "PROJECT_INVITATION",
                notification.getTargetType()
        );

        assertEquals(
                50L,
                notification.getTargetId()
        );

        assertEquals(
                4L,
                notification.getProjectId()
        );
        verify(operationLogService).record(
                eq(4L), eq(1L), eq("PROJECT_INVITATION"), eq(50L),
                eq("SEND_PROJECT_INVITATION"), anyString(), isNull(), any());
    }

    /**
     * Jack 接受 Tom 的邀请
     * → Jack 加入项目
     * → Tom 收到“邀请已接受”通知
     */
    @Test
    void acceptingInvitationNotifiesInviter() {

        ProjectInvitation invitation =
                new ProjectInvitation();

        invitation.setId(50L);
        invitation.setProjectId(4L);

        // Tom
        invitation.setInviterId(1L);

        // Jack
        invitation.setInviteeId(2L);

        invitation.setStatus("PENDING");

        invitation.setExpiresAt(
                LocalDateTime.now()
                        .plusDays(1)
        );

        Project project =
                new Project();

        project.setId(4L);
        project.setName("FlowDesk");
        project.setStatus("IN_PROGRESS");

        // 当前登录用户：Jack
        UserContext.set(
                new CurrentUser(2L, "USER")
        );

        when(projectInvitationMapper
                .selectById(50L))
                .thenReturn(invitation);

        when(projectMapper.selectByIdForUpdate(4L))
                .thenReturn(project);

        User jack = new User();
        jack.setId(2L);
        jack.setSystemRole("USER");
        jack.setStatus("ACTIVE");
        when(userMapper.selectById(2L)).thenReturn(jack);

        // Jack 之前不是项目成员
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(null);

        projectInvitationService
                .acceptInvitation(50L);

        // 邀请状态变为 ACCEPTED
        assertEquals(
                "ACCEPTED",
                invitation.getStatus()
        );

        // Jack 应该被加入项目
        verify(projectMemberMapper)
                .insert(
                        any(ProjectMember.class)
                );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(
                        captor.capture()
                );

        CreateNotificationDTO notification =
                captor.getValue();

        // 收件人：Tom
        assertEquals(
                1L,
                notification.getRecipientId()
        );

        // 操作人：Jack
        assertEquals(
                2L,
                notification.getActorId()
        );

        assertEquals(
                "PROJECT_INVITATION_ACCEPTED",
                notification.getType()
        );

        // Tom 可以选择点击“进入项目”
        assertEquals(
                "PROJECT",
                notification.getTargetType()
        );

        assertEquals(
                4L,
                notification.getTargetId()
        );

        assertEquals(
                4L,
                notification.getProjectId()
        );
    }

    /**
     * Jack 拒绝 Tom 的邀请
     * → Tom 收到“邀请已拒绝”通知
     */
    @Test
    void rejectingInvitationNotifiesInviter() {

        ProjectInvitation invitation =
                new ProjectInvitation();

        invitation.setId(50L);
        invitation.setProjectId(4L);

        // Tom
        invitation.setInviterId(1L);

        // Jack
        invitation.setInviteeId(2L);

        invitation.setStatus("PENDING");

        invitation.setExpiresAt(
                LocalDateTime.now()
                        .plusDays(1)
        );

        // 当前登录用户：Jack
        UserContext.set(
                new CurrentUser(2L, "USER")
        );

        when(projectInvitationMapper
                .selectById(50L))
                .thenReturn(invitation);

        projectInvitationService
                .rejectInvitation(50L);

        // 邀请变为 REJECTED
        assertEquals(
                "REJECTED",
                invitation.getStatus()
        );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(
                        captor.capture()
                );

        CreateNotificationDTO notification =
                captor.getValue();

        // 收件人：Tom
        assertEquals(
                1L,
                notification.getRecipientId()
        );

        // 操作人：Jack
        assertEquals(
                2L,
                notification.getActorId()
        );

        assertEquals(
                "PROJECT_INVITATION_REJECTED",
                notification.getType()
        );

        // Tom 可以自己决定是否进入项目
        assertEquals(
                "PROJECT",
                notification.getTargetType()
        );

        assertEquals(
                4L,
                notification.getTargetId()
        );

        assertEquals(
                4L,
                notification.getProjectId()
        );
    }

    @Test
    void developerCannotSendInvitation() {
        UserContext.set(new CurrentUser(2L, "USER"));
        SendInvitationDTO dto = new SendInvitationDTO();
        dto.setUsername("jack");
        doThrow(new BusinessException(403, "你不是该项目负责人"))
                .when(projectPermissionService).requireProjectManager(4L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> projectInvitationService.sendInvitation(4L, dto)
        );

        assertEquals(403, exception.getCode());
        verify(projectInvitationMapper, never()).insert(any(ProjectInvitation.class));
    }

    @Test
    void activeMemberCannotBeInvitedAgain() {
        UserContext.set(new CurrentUser(1L, "USER"));
        SendInvitationDTO dto = new SendInvitationDTO();
        dto.setUsername("jack");
        when(projectMapper.selectByIdForUpdate(4L)).thenReturn(activeProject());
        when(userMapper.selectOne(any())).thenReturn(activeUser(2L, "USER"));
        ProjectMember member = new ProjectMember();
        member.setStatus("ACTIVE");
        when(projectMemberMapper.selectOne(any())).thenReturn(member);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> projectInvitationService.sendInvitation(4L, dto)
        );

        assertEquals(409, exception.getCode());
    }

    @Test
    void validPendingInvitationCannotBeDuplicated() {
        UserContext.set(new CurrentUser(1L, "USER"));
        SendInvitationDTO dto = new SendInvitationDTO();
        dto.setUsername("jack");
        when(projectMapper.selectByIdForUpdate(4L)).thenReturn(activeProject());
        when(userMapper.selectOne(any())).thenReturn(activeUser(2L, "USER"));
        when(projectMemberMapper.selectOne(any())).thenReturn(null);
        when(projectInvitationMapper.selectCount(any())).thenReturn(1L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> projectInvitationService.sendInvitation(4L, dto)
        );

        assertEquals(409, exception.getCode());
        verify(projectInvitationMapper, never()).insert(any(ProjectInvitation.class));
    }

    @Test
    void nonInviteeCannotAcceptOrRejectInvitation() {
        ProjectInvitation invitation = pendingInvitation();
        UserContext.set(new CurrentUser(3L, "USER"));
        when(projectInvitationMapper.selectById(50L)).thenReturn(invitation);

        assertEquals(403, assertThrows(BusinessException.class,
                () -> projectInvitationService.acceptInvitation(50L)).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> projectInvitationService.rejectInvitation(50L)).getCode());
    }

    @Test
    void expiredInvitationCannotBeAccepted() {
        ProjectInvitation invitation = pendingInvitation();
        invitation.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        UserContext.set(new CurrentUser(2L, "USER"));
        when(projectInvitationMapper.selectById(50L)).thenReturn(invitation);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> projectInvitationService.acceptInvitation(50L)).getCode());
    }

    @Test
    void acceptedOrRejectedInvitationCannotBeAcceptedAgain() {
        ProjectInvitation invitation = pendingInvitation();
        UserContext.set(new CurrentUser(2L, "USER"));
        when(projectInvitationMapper.selectById(50L)).thenReturn(invitation);

        invitation.setStatus("ACCEPTED");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> projectInvitationService.acceptInvitation(50L)).getCode());

        invitation.setStatus("REJECTED");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> projectInvitationService.acceptInvitation(50L)).getCode());
    }

    @Test
    void concurrentAcceptanceIsRejectedByConditionalUpdate() {
        ProjectInvitation invitation = pendingInvitation();
        UserContext.set(new CurrentUser(2L, "USER"));
        when(projectInvitationMapper.selectById(50L)).thenReturn(invitation);
        when(userMapper.selectById(2L)).thenReturn(activeUser(2L, "USER"));
        when(projectMapper.selectByIdForUpdate(4L)).thenReturn(activeProject());
        when(projectInvitationMapper.transitionPending(
                anyLong(), anyString(), any(LocalDateTime.class)
        )).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> projectInvitationService.acceptInvitation(50L)
        );

        assertEquals(409, exception.getCode());
        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
        verify(notificationService, never()).createNotification(any(CreateNotificationDTO.class));
        verify(operationLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reactivatingFormerManagerDoesNotDemoteRole() {
        ProjectInvitation invitation = pendingInvitation();
        ProjectMember member = new ProjectMember();
        member.setRole("PROJECT_MANAGER");
        member.setStatus("INACTIVE");
        UserContext.set(new CurrentUser(2L, "USER"));
        when(projectInvitationMapper.selectById(50L)).thenReturn(invitation);
        when(userMapper.selectById(2L)).thenReturn(activeUser(2L, "USER"));
        when(projectMapper.selectByIdForUpdate(4L)).thenReturn(activeProject());
        when(projectMemberMapper.selectOne(any())).thenReturn(member);

        projectInvitationService.acceptInvitation(50L);

        assertEquals("PROJECT_MANAGER", member.getRole());
        assertEquals("ACTIVE", member.getStatus());
        verify(projectMemberMapper).updateById(member);
    }

    private ProjectInvitation pendingInvitation() {
        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setId(50L);
        invitation.setProjectId(4L);
        invitation.setInviterId(1L);
        invitation.setInviteeId(2L);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
        return invitation;
    }

    private Project activeProject() {
        Project project = new Project();
        project.setId(4L);
        project.setName("FlowDesk");
        project.setStatus("IN_PROGRESS");
        return project;
    }

    private User activeUser(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setStatus("ACTIVE");
        user.setSystemRole(role);
        return user;
    }
}
