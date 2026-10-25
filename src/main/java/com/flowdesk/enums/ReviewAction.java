package com.flowdesk.enums;

/** 审核操作代码，与各业务的审核结果状态分开维护。 */
public enum ReviewAction {
    APPROVE("APPROVE"),
    REJECT("REJECT");

    private final String code;

    ReviewAction(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean matches(String code) {
        return this.code.equals(code);
    }

    public static boolean isKnownCode(String code) {
        for (ReviewAction value : values()) {
            if (value.matches(code)) return true;
        }
        return false;
    }
}
