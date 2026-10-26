package com.flowdesk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectMemberStatus {
    ACTIVE,
    INACTIVE;

    @JsonValue
    public String getCode() {
        return name();
    }

    @JsonCreator
    public static ProjectMemberStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        return valueOf(code);
    }
}
