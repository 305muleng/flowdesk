package com.flowdesk;

import com.flowdesk.context.*;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PendingRequestAvailabilityTest {

    ProjectMapper projects = mock(ProjectMapper.class);
    ProjectInvitationMapper invitations = mock(ProjectInvitationMapper.class);
    ProjectMemberMapper members = mock(ProjectMemberMapper.class);
    UserMapper users = mock(UserMapper.class);
    ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    NotificationService notifications = mock(NotificationService.class);
    OperationLogService logs = mock(OperationLogService.class);
    TaskMapper tasks = mock(TaskMapper.class);
    TaskRequestMapper requests = mock(TaskRequestMapper.class);
    MemberLeaveRequestMapper leaves = mock(MemberLeaveRequestMapper.class);
    ProjectManagerTransferMapper transfers = mock(ProjectManagerTransferMapper.class);
    TaskRequestService requestService = new TaskRequestService(
        requests,
        projects,
        permissions,
        tasks,
        members,
        notifications,
        users,
        logs,
        mock(com.flowdesk.cache.ProjectTaskSummaryCache.class)
    );
    ProjectInvitationService invitationService = new ProjectInvitationService(
        invitations,
        members,
        users,
        permissions,
        projects,
        notifications,
        logs,
        7
    );
    MemberLeaveRequestService leaveService = new MemberLeaveRequestService(
        leaves,
        projects,
        members,
        permissions,
        tasks,
        notifications,
        logs,
        requests,
        7
    );
    ProjectManagerTransferService transferService = new ProjectManagerTransferService(
        transfers,
        projects,
        users,
        tasks,
        permissions,
        notifications,
        logs,
        mock(PasswordEncoder.class),
        members,
        requests,
        leaves,
        7
    );

    @BeforeEach
    void setup() {
        UserContext.set(new CurrentUser(2L, "USER"));
    }

    @AfterEach
    void cleanup() {
        UserContext.remove();
    }

    ProjectInvitation invitation() {
        ProjectInvitation i = new ProjectInvitation();
        i.setId(10L);
        i.setProjectId(4L);
        i.setInviteeId(2L);
        i.setInviterId(1L);
        i.setStatus("PENDING");
        i.setExpiresAt(LocalDateTime.now().plusDays(1));
        return i;
    }

    Project project(String status) {
        Project p = new Project();
        p.setId(4L);
        p.setStatus(status);
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_ACCEPTANCE", "COMPLETED", "ARCHIVED", "CANCELLED" })
    void freezePreventsRejectAndWithdrawWithoutChangingRequestMeaning(String status) {
        when(projects.selectByIdForUpdate(4L)).thenReturn(project(status));
        ProjectInvitation i = invitation();
        when(invitations.selectById(10L)).thenReturn(i);
        assertEquals(409, assertThrows(BusinessException.class, () -> invitationService.rejectInvitation(10L)).getCode());
        assertEquals("PENDING", i.getStatus());
        MemberLeaveRequest leave = new MemberLeaveRequest();
        leave.setProjectId(4L);
        leave.setApplicantId(2L);
        leave.setStatus("PENDING");
        leave.setExpiresAt(i.getExpiresAt());
        when(leaves.selectById(11L)).thenReturn(leave);
        assertEquals(409, assertThrows(BusinessException.class, () -> leaveService.cancelLeaveRequest(4L, 11L)).getCode());
        assertEquals("PENDING", leave.getStatus());
        ProjectManagerTransfer transfer = new ProjectManagerTransfer();
        transfer.setProjectId(4L);
        transfer.setToUserId(2L);
        transfer.setFromUserId(2L);
        transfer.setStatus("PENDING");
        transfer.setExpiresAt(i.getExpiresAt());
        when(transfers.selectById(12L)).thenReturn(transfer);
        assertEquals(409, assertThrows(BusinessException.class, () -> transferService.rejectTransfer(4L, 12L)).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> transferService.cancelTransfer(4L, 12L)).getCode());
        verify(invitations, never()).transitionPending(anyLong(), anyString(), any());
        verify(leaves, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(transfers, never()).rejectIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(transfers, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        TaskRequest request = new TaskRequest();
        request.setProjectId(4L);
        request.setRequesterId(2L);
        request.setStatus("PENDING");
        when(requests.selectById(13L)).thenReturn(request);
        assertEquals(409, assertThrows(BusinessException.class, () -> requestService.cancelTaskRequest(4L, 13L)).getCode());
        verify(requests, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(logs, notifications);
    }

    @Test
    void unexpiredInvitationCanBeProcessedAfterAcceptanceRejection() {
        ProjectInvitation i = invitation();
        when(invitations.selectById(10L)).thenReturn(i);
        when(projects.selectByIdForUpdate(4L)).thenReturn(project("PENDING_ACCEPTANCE"));
        assertThrows(BusinessException.class, () -> invitationService.rejectInvitation(10L));
        assertEquals("PENDING", i.getStatus());
        when(projects.selectByIdForUpdate(4L)).thenReturn(project("IN_PROGRESS"));
        when(invitations.transitionPending(eq(10L), eq("REJECTED"), any())).thenReturn(1);
        invitationService.rejectInvitation(10L);
        assertEquals("REJECTED", i.getStatus());
    }

    @Test
    void expiredInvitationCannotBeRejectedAndKeepsPendingStorageState() {
        ProjectInvitation i = invitation();
        i.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(invitations.selectById(10L)).thenReturn(i);
        var error = assertThrows(BusinessException.class, () -> invitationService.rejectInvitation(10L));
        assertTrue(error.getMessage().contains("过期"));
        assertEquals("PENDING", i.getStatus());
        verify(invitations, never()).transitionPending(anyLong(), anyString(), any());
        verifyNoInteractions(logs, notifications);
    }

    @Test
    void expiryDuringProjectLockWaitIsRecheckedByConditionalWrite() {
        when(invitations.selectById(10L)).thenReturn(invitation());
        when(projects.selectByIdForUpdate(4L)).thenReturn(project("IN_PROGRESS"));
        when(invitations.transitionPending(eq(10L), eq("REJECTED"), any())).thenReturn(0);
        assertEquals(409, assertThrows(BusinessException.class, () -> invitationService.rejectInvitation(10L)).getCode());
        verifyNoInteractions(logs, notifications);
    }
}
