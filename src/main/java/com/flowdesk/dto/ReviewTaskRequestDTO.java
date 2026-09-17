package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public class ReviewTaskRequestDTO {

    @NotBlank(message = "审批结果不能为空")
    private String action;

    private String reviewNote;

    private Long assigneeId;

    private String priority;

    private LocalDateTime deadline;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public void setDeadline(LocalDateTime deadline) {
        this.deadline = deadline;
    }
}