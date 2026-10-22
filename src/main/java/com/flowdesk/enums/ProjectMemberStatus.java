package com.flowdesk.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectMemberStatus implements IEnum<String> {
    ACTIVE("ACTIVE"),
    INACTIVE("INACTIVE");

    private final String code;

    ProjectMemberStatus(String code) {
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
    public static ProjectMemberStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectMemberStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ProjectMemberStatus code: " + code);
    }
}
