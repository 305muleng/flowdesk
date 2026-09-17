package com.flowdesk.dto;

import jakarta.validation.constraints.NotNull;

public class AssignTaskDTO {

    @NotNull(message = "任务负责人不能为空")
    private Long assigneeId;

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }
}