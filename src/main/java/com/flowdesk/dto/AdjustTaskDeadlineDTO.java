package com.flowdesk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record AdjustTaskDeadlineDTO(
    @NotNull(message = "原截止时间不能为空") LocalDateTime expectedDeadline,
    @NotNull(message = "新截止时间不能为空") LocalDateTime deadline,
    @NotBlank(message = "请填写调整原因") @Size(max = 500, message = "调整原因不能超过500个字符") String reason
) {}
