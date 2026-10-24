package com.flowdesk.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;

public enum TaskStatus implements IEnum<String> {
    TODO("TODO"),
    IN_PROGRESS("IN_PROGRESS"),
    REVIEW("REVIEW"),
    DONE("DONE"),
    CANCELLED("CANCELLED");

    private static final Set<TaskStatus> UNFINISHED_STATUSES = Set.of(TODO, IN_PROGRESS, REVIEW);

    private final String code;

    TaskStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @Override
    public String getValue() {
        return code;
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
        for (TaskStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid TaskStatus code: " + code);
    }
}
