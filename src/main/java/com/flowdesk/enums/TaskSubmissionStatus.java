package com.flowdesk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskSubmissionStatus {
    PENDING,
    APPROVED,
    REJECTED;

    @JsonValue
    public String getCode() {
        return name();
    }

    @JsonCreator
    public static TaskSubmissionStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        return valueOf(code);
    }
}
