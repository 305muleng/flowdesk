package com.flowdesk;

import com.flowdesk.mapper.*;
import com.flowdesk.service.ProjectRequestClosureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectRequestClosureTest {

    ProjectInvitationMapper invitations = mock(ProjectInvitationMapper.class);
    MemberLeaveRequestMapper leaves = mock(MemberLeaveRequestMapper.class);
    ProjectManagerTransferMapper transfers = mock(ProjectManagerTransferMapper.class);
    TaskRequestMapper tasks = mock(TaskRequestMapper.class);
    ProjectRequestClosureService service = new ProjectRequestClosureService(invitations, leaves, transfers, tasks);
    LocalDateTime now = LocalDateTime.of(2026, 10, 18, 12, 30);

    @Test
    void completedClosesThreeWithSameProjectTimeAndReason() {
        service.closeForCompletedProject(4L, now);
        var order = inOrder(invitations, leaves, transfers);
        order.verify(invitations).closePendingForProject(eq(4L), same(now), eq("PROJECT_COMPLETED"));
        order.verify(leaves).closePendingForProject(eq(4L), same(now), eq("PROJECT_COMPLETED"));
        order.verify(transfers).closePendingForProject(eq(4L), same(now), eq("PROJECT_COMPLETED"));
        verifyNoInteractions(tasks);
    }

    @Test
    void cancellationClosesFourAndZeroRowsOrRepeatedCloseAreNormal() {
        assertDoesNotThrow(() -> service.closeForCancelledProject(4L, now));
        assertDoesNotThrow(() -> service.closeForCancelledProject(4L, now.plusSeconds(1)));
        for (LocalDateTime time : new LocalDateTime[] { now, now.plusSeconds(1) }) {
            verify(invitations).closePendingForProject(4L, time, "PROJECT_CANCELLED");
            verify(leaves).closePendingForProject(4L, time, "PROJECT_CANCELLED");
            verify(transfers).closePendingForProject(4L, time, "PROJECT_CANCELLED");
            verify(tasks).closePendingForProject(4L, time, "PROJECT_CANCELLED");
        }
    }

    @ParameterizedTest
    @CsvSource({ "true,0", "true,1", "true,2", "false,0", "false,1", "false,2", "false,3" })
    void failureAtAnyMapperEscapesAndStopsFollowingUpdates(boolean completed, int failingStep) {
        RuntimeException failure = new IllegalStateException("test closure failure");
        switch (failingStep) {
            case 0 -> when(invitations.closePendingForProject(anyLong(), any(), anyString())).thenThrow(failure);
            case 1 -> when(leaves.closePendingForProject(anyLong(), any(), anyString())).thenThrow(failure);
            case 2 -> when(transfers.closePendingForProject(anyLong(), any(), anyString())).thenThrow(failure);
            case 3 -> when(tasks.closePendingForProject(anyLong(), any(), anyString())).thenThrow(failure);
        }
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            if (completed) service.closeForCompletedProject(4L, now);
            else service.closeForCancelledProject(4L, now);
        });
        assertSame(failure, thrown);
        if (failingStep < 1) verifyNoInteractions(leaves);
        if (failingStep < 2) verifyNoInteractions(transfers);
        if (failingStep < 3) verifyNoInteractions(tasks);
    }
}
