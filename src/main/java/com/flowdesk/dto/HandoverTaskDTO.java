package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HandoverTaskDTO(
    @NotNull(message = "原任务负责人不能为空") Long expectedAssigneeId,
    @NotNull(message = "新任务负责人不能为空") Long assigneeId,
    @NotBlank(message = "交接原因必填") @Size(max = 500, message = "交接原因不能超过500字") String reason
) {}
