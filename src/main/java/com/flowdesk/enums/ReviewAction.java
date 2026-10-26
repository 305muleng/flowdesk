package com.flowdesk.enums;

/** 审核操作代码，与各业务的审核结果状态分开维护。 */
public enum ReviewAction {
    APPROVE,
    REJECT;

    public String getCode() {
        return name();
    }

    public boolean matches(String code) {
        return name().equals(code);
    }

    public static boolean isKnownCode(String code) {
        for (ReviewAction value : values()) {
            if (value.matches(code)) return true;
        }
        return false;
    }
}
