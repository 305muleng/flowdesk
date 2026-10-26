package com.flowdesk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;

public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    REVIEW,
    DONE,
    CANCELLED;

    private static final Set<TaskStatus> UNFINISHED_STATUSES = Set.of(TODO, IN_PROGRESS, REVIEW);

    @JsonValue
    public String getCode() {
        return name();
    }

    /** 未完成任务会阻止验收或成员离开，统一维护其业务范围。 */
    public static Set<TaskStatus> unfinishedStatuses() {
        return UNFINISHED_STATUSES;
    }

    @JsonCreator
    public static TaskStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        return valueOf(code);
    }
}
