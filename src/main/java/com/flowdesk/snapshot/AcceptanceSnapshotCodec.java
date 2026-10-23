package com.flowdesk.snapshot;

import com.flowdesk.exception.BusinessException;
import com.flowdesk.vo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Component
public class AcceptanceSnapshotCodec {
    public static final int VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(AcceptanceSnapshotCodec.class);
    private final ObjectMapper mapper;

    public AcceptanceSnapshotCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public String encode(ProjectAcceptanceDetailVO detail) {
        try {
            var members = detail.getMembers().stream().map(member -> new AcceptanceSnapshotV1.Member(
                    member.getUserId(), member.getUserName(), member.getRole(), member.getCompletedTaskCount(),
                    member.getCompletedTasks().stream().map(task -> new AcceptanceSnapshotV1.CompletedTask(
                            task.getTaskId(), task.getTitle(), task.getCompletedAt())).toList())).toList();
            var cancelled = detail.getCancelledTasks().stream().map(task -> new AcceptanceSnapshotV1.CancelledTask(
                    task.getTaskId(), task.getTitle(), task.getCancelReason(), task.getCancelledAt(),
                    task.getAssigneeId(), task.getAssigneeName())).toList();
            var snapshot = new AcceptanceSnapshotV1(detail.getProjectId(), detail.getProjectName(),
                    detail.getProjectDescription(), detail.getProjectGoal(), detail.getRepositoryUrl(),
                    detail.getDeployUrl(), detail.getDocumentUrl(), detail.getSubmitterId(), detail.getSubmitterName(),
                    detail.getTotalTaskCount(), detail.getCompletedTaskCount(), detail.getCancelledTaskCount(),
                    detail.getCompletionRate(), members, cancelled);
            validate(snapshot);
            return mapper.writeValueAsString(snapshot);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            log.error("Cannot encode acceptance snapshot for projectId={}", detail.getProjectId());
            throw new BusinessException(500, "保存验收快照失败，请稍后重试");
        }
    }

    public void apply(Integer version, String json, ProjectAcceptanceDetailVO detail) {
        try {
            require(Integer.valueOf(VERSION).equals(version) && json != null && !json.isBlank());
            var snapshot = mapper.readValue(json, AcceptanceSnapshotV1.class);
            validate(snapshot);
            require(Objects.equals(snapshot.projectId(), detail.getProjectId()));
            require(Objects.equals(snapshot.submitterId(), detail.getSubmitterId()));
            detail.setProjectName(snapshot.projectName());
            detail.setProjectDescription(snapshot.projectDescription());
            detail.setProjectGoal(snapshot.projectGoal());
            detail.setRepositoryUrl(snapshot.repositoryUrl());
            detail.setDeployUrl(snapshot.deployUrl());
            detail.setDocumentUrl(snapshot.documentUrl());
            detail.setSubmitterName(snapshot.submitterName());
            detail.setTotalTaskCount(snapshot.totalTaskCount());
            detail.setCompletedTaskCount(snapshot.completedTaskCount());
            detail.setCancelledTaskCount(snapshot.cancelledTaskCount());
            detail.setCompletionRate(snapshot.completionRate());
            detail.setMembers(snapshot.members().stream().map(member -> {
                var value = new MemberAcceptanceVO();
                value.setUserId(member.userId()); value.setUserName(member.userName()); value.setRole(member.role());
                value.setCompletedTaskCount(member.completedTaskCount());
                value.setCompletedTasks(member.completedTasks().stream().map(task -> {
                    var item = new TaskSimpleVO(); item.setTaskId(task.taskId()); item.setTitle(task.title());
                    item.setCompletedAt(task.completedAt()); return item;
                }).toList());
                return value;
            }).toList());
            detail.setCancelledTasks(snapshot.cancelledTasks().stream().map(task -> {
                var value = new CancelledTaskVO(); value.setTaskId(task.taskId()); value.setTitle(task.title());
                value.setCancelReason(task.cancelReason()); value.setCancelledAt(task.cancelledAt());
                value.setAssigneeId(task.assigneeId()); value.setAssigneeName(task.assigneeName()); return value;
            }).toList());
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            // Do not log the document or silently substitute current project data.
            log.error("Cannot read acceptance snapshot: acceptanceId={}, version={}", detail.getId(), version);
            throw new BusinessException(500, "验收快照数据不可用，请联系管理员");
        }
    }

    private void validate(AcceptanceSnapshotV1 value) {
        require(value != null && value.projectId() != null && value.submitterId() != null);
        require(text(value.projectName()) && text(value.submitterName()));
        require(text(value.repositoryUrl()) || text(value.deployUrl()) || text(value.documentUrl()));
        require(value.totalTaskCount() != null && value.completedTaskCount() != null && value.cancelledTaskCount() != null);
        require(value.totalTaskCount() >= 0 && value.completedTaskCount() >= 0 && value.cancelledTaskCount() >= 0);
        require((long) value.completedTaskCount() + value.cancelledTaskCount() == value.totalTaskCount());
        require(value.completionRate() != null && Double.isFinite(value.completionRate()));
        long denominator = (long) value.completedTaskCount() + value.cancelledTaskCount();
        double rate = denominator == 0 ? 0 : Math.round(value.completedTaskCount() * 10000.0 / denominator) / 100.0;
        require(Double.compare(value.completionRate(), rate) == 0);
        require(value.members() != null && value.cancelledTasks() != null);
        require(value.cancelledTasks().size() == value.cancelledTaskCount());
        Set<Long> memberIds = new HashSet<>();
        Set<Long> taskIds = new HashSet<>();
        long attributedTasks = 0;
        for (var member : value.members()) {
            require(member != null && member.userId() != null && memberIds.add(member.userId()));
            require(text(member.userName()) && Set.of("PROJECT_MANAGER", "DEVELOPER").contains(member.role()));
            require(member.completedTasks() != null && member.completedTaskCount() != null);
            require(member.completedTaskCount() == member.completedTasks().size());
            attributedTasks += member.completedTaskCount();
            for (var task : member.completedTasks()) {
                require(task != null && task.taskId() != null && text(task.title()) && taskIds.add(task.taskId()));
            }
        }
        require(attributedTasks <= value.completedTaskCount());
        for (var task : value.cancelledTasks()) {
            require(task != null && task.taskId() != null && text(task.title()) && taskIds.add(task.taskId()));
        }
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid snapshot structure"); }
}
