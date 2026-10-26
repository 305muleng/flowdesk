package com.flowdesk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectManagerTransferStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED,
    CLOSED;

    @JsonValue
    public String getCode() {
        return name();
    }

    @JsonCreator
    public static ProjectManagerTransferStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        return valueOf(code);
    }
}
