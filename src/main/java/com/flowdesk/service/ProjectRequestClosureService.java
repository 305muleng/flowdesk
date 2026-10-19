package com.flowdesk.service;

import com.flowdesk.mapper.MemberLeaveRequestMapper;
import com.flowdesk.mapper.ProjectInvitationMapper;
import com.flowdesk.mapper.ProjectManagerTransferMapper;
import com.flowdesk.mapper.TaskRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ProjectRequestClosureService {

    private final ProjectInvitationMapper invitations;
    private final MemberLeaveRequestMapper leaves;
    private final ProjectManagerTransferMapper transfers;
    private final TaskRequestMapper taskRequests;

    public ProjectRequestClosureService(
        ProjectInvitationMapper invitations,
        MemberLeaveRequestMapper leaves,
        ProjectManagerTransferMapper transfers,
        TaskRequestMapper taskRequests
    ) {
        this.invitations = invitations;
        this.leaves = leaves;
        this.transfers = transfers;
        this.taskRequests = taskRequests;
    }

    public void closeForCompletedProject(Long projectId, LocalDateTime closedAt) {
        closeMemberRequests(projectId, closedAt, "PROJECT_COMPLETED");
    }

    public void closeForCancelledProject(Long projectId, LocalDateTime closedAt) {
        closeMemberRequests(projectId, closedAt, "PROJECT_CANCELLED");
        taskRequests.closePendingForProject(projectId, closedAt, "PROJECT_CANCELLED");
    }

    private void closeMemberRequests(Long projectId, LocalDateTime closedAt, String reason) {
        invitations.closePendingForProject(projectId, closedAt, reason);
        leaves.closePendingForProject(projectId, closedAt, reason);
        transfers.closePendingForProject(projectId, closedAt, reason);
    }
}
