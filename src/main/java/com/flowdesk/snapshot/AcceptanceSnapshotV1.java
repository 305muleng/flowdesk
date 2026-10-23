package com.flowdesk.snapshot;

import java.time.LocalDateTime;
import java.util.List;

/** Persisted V1 contract. Keep independent of the live API's mutable VO classes. */
public record AcceptanceSnapshotV1(
        Long projectId, String projectName, String projectDescription, String projectGoal,
        String repositoryUrl, String deployUrl, String documentUrl,
        Long submitterId, String submitterName,
        Integer totalTaskCount, Integer completedTaskCount, Integer cancelledTaskCount,
        Double completionRate, List<Member> members, List<CancelledTask> cancelledTasks) {

    public record CompletedTask(Long taskId, String title, LocalDateTime completedAt) {}
    public record Member(Long userId, String userName, String role,
                         Integer completedTaskCount, List<CompletedTask> completedTasks) {}
    public record CancelledTask(Long taskId, String title, String cancelReason,
                                LocalDateTime cancelledAt, Long assigneeId, String assigneeName) {}
}
