package com.flowdesk.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectAcceptanceStatus implements IEnum<String> {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED");

    private final String code;

    ProjectAcceptanceStatus(String code) {
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
    public static ProjectAcceptanceStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectAcceptanceStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ProjectAcceptanceStatus code: " + code);
    }
}
