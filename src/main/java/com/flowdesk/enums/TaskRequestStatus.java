package com.flowdesk.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskRequestStatus implements IEnum<String> {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    CANCELLED("CANCELLED"),
    CLOSED("CLOSED");

    private final String code;

    TaskRequestStatus(String code) {
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

    @JsonCreator
    public static TaskRequestStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TaskRequestStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid TaskRequestStatus code: " + code);
    }
}
