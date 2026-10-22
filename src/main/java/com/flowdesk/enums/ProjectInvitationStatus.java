package com.flowdesk.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectInvitationStatus implements IEnum<String> {
    PENDING("PENDING"),
    ACCEPTED("ACCEPTED"),
    REJECTED("REJECTED"),
    CLOSED("CLOSED");

    private final String code;

    ProjectInvitationStatus(String code) {
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
    public static ProjectInvitationStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProjectInvitationStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ProjectInvitationStatus code: " + code);
    }
}
