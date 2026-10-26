package com.flowdesk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum UserStatus {
    ACTIVE,
    DISABLED;

    @JsonValue
    public String getCode() {
        return name();
    }

    @JsonCreator
    public static UserStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        return valueOf(code);
    }
}
