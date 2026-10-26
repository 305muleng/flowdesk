package com.flowdesk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectStatus {
    PREPARING,
    IN_PROGRESS,
    PENDING_ACCEPTANCE,
    COMPLETED,
    ARCHIVED,
    CANCELLED;

    @JsonValue
    public String getCode() {
        return name();
    }

    @JsonCreator
    public static ProjectStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        return valueOf(code);
    }
}
