package com.flowdesk.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectStatus implements IEnum<String> {
    PREPARING("PREPARING"),
    IN_PROGRESS("IN_PROGRESS"),
    PENDING_ACCEPTANCE("PENDING_ACCEPTANCE"),
    COMPLETED("COMPLETED"),
    ARCHIVED("ARCHIVED"),
    CANCELLED("CANCELLED");

    private final String code;

    ProjectStatus(String code) {
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
    public static ProjectStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ProjectStatus code: " + code);
    }
}
