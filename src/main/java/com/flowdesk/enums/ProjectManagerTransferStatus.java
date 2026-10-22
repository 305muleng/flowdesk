package com.flowdesk.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectManagerTransferStatus implements IEnum<String> {
    PENDING("PENDING"),
    ACCEPTED("ACCEPTED"),
    REJECTED("REJECTED"),
    CANCELLED("CANCELLED"),
    CLOSED("CLOSED");

    private final String code;

    ProjectManagerTransferStatus(String code) {
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
    public static ProjectManagerTransferStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectManagerTransferStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ProjectManagerTransferStatus code: " + code);
    }
}
