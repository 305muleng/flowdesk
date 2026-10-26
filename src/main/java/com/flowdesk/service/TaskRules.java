package com.flowdesk.service;

import com.flowdesk.enums.TaskPriority;
import com.flowdesk.exception.BusinessException;
import java.util.Locale;

final class TaskRules {
    private TaskRules() {}

    static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) return TaskPriority.MEDIUM.getCode();
        String code = priority.trim().toUpperCase(Locale.ROOT);
        if (!TaskPriority.isKnownCode(code)) {
            throw new BusinessException(400, "任务优先级只能是 LOW、MEDIUM 或 HIGH");
        }
        return code;
    }
}
