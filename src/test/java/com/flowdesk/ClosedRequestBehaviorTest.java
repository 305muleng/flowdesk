package com.flowdesk;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.enums.ProjectManagerTransferStatus;
import com.flowdesk.enums.MemberLeaveRequestStatus;
import com.flowdesk.enums.TaskRequestStatus;
import com.flowdesk.enums.ProjectInvitationStatus;
import com.flowdesk.enums.ProjectStatus;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.*;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import com.flowdesk.vo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClosedRequestBehaviorTest {

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
    TaskRequestService taskRequestService = new TaskRequestService(
        requests,
        projects,
        permissions,
        tasks,
        members,
        notifications,
        users,
        logs,
        mock(ProjectTaskSummaryCache.class),
        mock(com.flowdesk.service.TaskReminderService.class)
    );

    @BeforeEach
    void setup() {
        UserContext.set(new CurrentUser(2L, "USER"));
    }

    @AfterEach
    void cleanup() {
        UserContext.remove();
    }

    @Test
    void closedInvitationRejectsAcceptAndRejectWithoutChangingMembership() {
        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setProjectId(4L);
        invitation.setInviteeId(2L);
        invitation.setStatus(ProjectInvitationStatus.CLOSED);
        when(invitations.selectById(10L)).thenReturn(invitation);
        conflict(() -> invitationService.acceptInvitation(10L));
        conflict(() -> invitationService.rejectInvitation(10L));
        verify(invitations, never()).transitionPending(anyLong(), any(ProjectInvitationStatus.class), any());
        verifyNoInteractions(members, tasks, logs, notifications);
    }

    @Test
    void closedLeaveRequestRejectsReviewAndWithdrawal() {
        Project p = new Project();
        p.setId(4L);
        p.setStatus(ProjectStatus.IN_PROGRESS);
        when(projects.selectByIdForUpdate(4L)).thenReturn(p);
        MemberLeaveRequest request = new MemberLeaveRequest();
        request.setProjectId(4L);
        request.setApplicantId(2L);
        request.setStatus(MemberLeaveRequestStatus.CLOSED);
        when(leaves.selectById(11L)).thenReturn(request);
        when(leaves.selectByIdForUpdate(11L)).thenReturn(request);
        ReviewMemberLeaveRequestDTO dto = new ReviewMemberLeaveRequestDTO();
        dto.setAction("APPROVE");
        conflict(() -> leaveService.reviewLeaveRequest(4L, 11L, dto));
        conflict(() -> leaveService.cancelLeaveRequest(4L, 11L));
        verify(leaves, never()).reviewIfPending(
            anyLong(),
            anyLong(),
            any(MemberLeaveRequestStatus.class),
            anyLong(),
            any(),
            any(),
            any()
        );
        verify(leaves, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(members, tasks, logs, notifications);
    }

    @Test
    void closedTransferRejectsAllActionsWithoutChangingMembership() {
        ProjectManagerTransfer t = new ProjectManagerTransfer();
        t.setProjectId(4L);
        t.setFromUserId(2L);
        t.setToUserId(2L);
        t.setStatus(ProjectManagerTransferStatus.CLOSED);
        when(transfers.selectById(12L)).thenReturn(t);
        conflict(() -> transferService.acceptTransfer(4L, 12L));
        conflict(() -> transferService.rejectTransfer(4L, 12L));
        conflict(() -> transferService.cancelTransfer(4L, 12L));
        verify(transfers, never()).acceptIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(transfers, never()).rejectIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(transfers, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(members, tasks, logs, notifications);
    }

    @Test
    void closedTaskRequestRejectsReviewAndWithdrawalWithoutCreatingTask() {
        Project p = new Project();
        p.setId(4L);
        p.setStatus(ProjectStatus.IN_PROGRESS);
        when(projects.selectByIdForUpdate(4L)).thenReturn(p);
        TaskRequest r = new TaskRequest();
        r.setProjectId(4L);
        r.setRequesterId(2L);
        r.setStatus(TaskRequestStatus.CLOSED);
        when(requests.selectById(13L)).thenReturn(r);
        ReviewTaskRequestDTO dto = new ReviewTaskRequestDTO();
        dto.setAction("APPROVE");
        conflict(() -> taskRequestService.reviewTaskRequest(4L, 13L, dto));
        conflict(() -> taskRequestService.cancelTaskRequest(4L, 13L));
        verify(requests, never()).reviewIfPending(anyLong(), anyLong(), any(TaskRequestStatus.class), anyLong(), any(), any());
        verify(requests, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(tasks, logs, notifications);
    }

    @Test
    void closedHistoryRemainsClosedPastExpiryForHistoricalParticipants() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        MemberLeaveRequestVO leave = new MemberLeaveRequestVO();
        leave.setApplicantId(2L);
        leave.setStatus(MemberLeaveRequestStatus.CLOSED.getCode());
        leave.setExpiresAt(past);
        leave.setClosedAt(past.minusDays(1));
        leave.setCloseReason("PROJECT_COMPLETED");
        when(leaves.selectDetail(4L, 11L)).thenReturn(leave);
        assertEquals("CLOSED", leaveService.getLeaveRequestDetail(4L, 11L).getStatus());
        assertEquals("PROJECT_COMPLETED", leave.getCloseReason());
        ProjectManagerTransferVO transfer = new ProjectManagerTransferVO();
        transfer.setFromUserId(1L);
        transfer.setToUserId(2L);
        transfer.setStatus(ProjectManagerTransferStatus.CLOSED.getCode());
        transfer.setExpiresAt(past);
        when(transfers.selectDetail(4L, 12L)).thenReturn(transfer);
        assertEquals("CLOSED", transferService.getTransferDetail(4L, 12L).getStatus());
        verifyNoInteractions(permissions, projects);
    }

    @Test
    void closedFilterAcceptedAndOnlyPendingInvitationBecomesExpired() {
        InvitationVO closed = new InvitationVO();
        closed.setStatus("CLOSED");
        closed.setExpiresAt(LocalDateTime.now().minusDays(1));
        InvitationVO pending = new InvitationVO();
        pending.setStatus("PENDING");
        pending.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(invitations.selectMyInvitations(eq(2L), eq("CLOSED"), any())).thenReturn(List.of(closed));
        assertSame(closed, invitationService.getMyInvitations(" closed ").get(0));
        assertFalse(closed.isExpired());
        when(invitations.selectMyInvitations(eq(2L), eq("ALL"), any())).thenReturn(List.of(closed, pending));
        invitationService.getMyInvitations("ALL");
        assertFalse(closed.isExpired());
        assertTrue(pending.isExpired());
        taskRequestService.getMyTaskRequests(" closed ");
        verify(requests).selectMyTaskRequests(2L, "CLOSED");
        Project p = new Project();
        p.setId(4L);
        when(projects.selectById(4L)).thenReturn(p);
        taskRequestService.getProjectTaskRequests(4L, "CLOSED");
        verify(requests).selectProjectTaskRequests(4L, "CLOSED");
    }

    @Test
    void closedHistoryDoesNotGrantUnrelatedUserProjectAccess() {
        MemberLeaveRequestVO leave = new MemberLeaveRequestVO();
        leave.setApplicantId(99L);
        leave.setStatus(MemberLeaveRequestStatus.CLOSED.getCode());
        when(leaves.selectDetail(4L, 11L)).thenReturn(leave);
        ProjectManagerTransferVO transfer = new ProjectManagerTransferVO();
        transfer.setFromUserId(98L);
        transfer.setToUserId(99L);
        transfer.setStatus(ProjectManagerTransferStatus.CLOSED.getCode());
        when(transfers.selectDetail(4L, 12L)).thenReturn(transfer);
        doThrow(new BusinessException(403, "非本项目负责人")).when(permissions).requireProjectManager(4L);
        assertEquals(403, assertThrows(BusinessException.class, () -> leaveService.getLeaveRequestDetail(4L, 11L)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> transferService.getTransferDetail(4L, 12L)).getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = { "COMPLETED", "CANCELLED" })
    void terminalProjectReadAfterLockRejectsEvenStalePendingRequests(String status) {
        Project project = new Project();
        project.setId(4L);
        project.setStatus(ProjectStatus.fromCode(status));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project);
        LocalDateTime expires = LocalDateTime.now().plusDays(1);
        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setProjectId(4L);
        invitation.setInviteeId(2L);
        invitation.setStatus(ProjectInvitationStatus.PENDING);
        invitation.setExpiresAt(expires);
        when(invitations.selectById(10L)).thenReturn(invitation);
        User user = new User();
        user.setId(2L);
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole("USER");
        when(users.selectById(2L)).thenReturn(user);
        MemberLeaveRequest leave = new MemberLeaveRequest();
        leave.setProjectId(4L);
        leave.setApplicantId(2L);
        leave.setStatus(MemberLeaveRequestStatus.PENDING);
        leave.setExpiresAt(expires);
        when(leaves.selectById(11L)).thenReturn(leave);
        ProjectManagerTransfer transfer = new ProjectManagerTransfer();
        transfer.setProjectId(4L);
        transfer.setToUserId(2L);
        transfer.setFromUserId(2L);
        transfer.setStatus(ProjectManagerTransferStatus.PENDING);
        transfer.setExpiresAt(expires);
        when(transfers.selectById(12L)).thenReturn(transfer);
        TaskRequest request = new TaskRequest();
        request.setProjectId(4L);
        request.setRequesterId(2L);
        request.setStatus(TaskRequestStatus.PENDING);
        when(requests.selectById(13L)).thenReturn(request);
        ReviewMemberLeaveRequestDTO leaveReview = new ReviewMemberLeaveRequestDTO();
        leaveReview.setAction("APPROVE");
        ReviewTaskRequestDTO taskReview = new ReviewTaskRequestDTO();
        taskReview.setAction("APPROVE");

        conflict(() -> invitationService.acceptInvitation(10L));
        conflict(() -> invitationService.rejectInvitation(10L));
        conflict(() -> leaveService.reviewLeaveRequest(4L, 11L, leaveReview));
        conflict(() -> leaveService.cancelLeaveRequest(4L, 11L));
        conflict(() -> transferService.acceptTransfer(4L, 12L));
        conflict(() -> transferService.rejectTransfer(4L, 12L));
        conflict(() -> transferService.cancelTransfer(4L, 12L));
        conflict(() -> taskRequestService.reviewTaskRequest(4L, 13L, taskReview));
        conflict(() -> taskRequestService.cancelTaskRequest(4L, 13L));
        verify(projects, times(9)).selectByIdForUpdate(4L);
        verify(invitations, never()).transitionPending(anyLong(), any(ProjectInvitationStatus.class), any());
        verify(leaves, never()).reviewIfPending(
            anyLong(),
            anyLong(),
            any(MemberLeaveRequestStatus.class),
            anyLong(),
            any(),
            any(),
            any()
        );
        verify(leaves, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(transfers, never()).acceptIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(transfers, never()).rejectIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(transfers, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any(), any());
        verify(requests, never()).reviewIfPending(anyLong(), anyLong(), any(TaskRequestStatus.class), anyLong(), any(), any());
        verify(requests, never()).cancelIfPending(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(members, tasks, logs, notifications);
    }

    void conflict(Runnable action) {
        assertEquals(409, assertThrows(BusinessException.class, action::run).getCode());
    }
}
