package com.flowdesk.enums;

/** 系统角色；项目内的身份由 ProjectRole 表达。 */
public enum SystemRole {
    USER,
    SYSTEM_ADMIN;

    public String getCode() {
        return name();
    }

    /** 精确匹配现有字符串代码，空值及未知值均不匹配。 */
    public boolean matches(String code) {
        return name().equals(code);
    }

    public static boolean isKnownCode(String code) {
        for (SystemRole role : values()) {
            if (role.matches(code)) return true;
        }
        return false;
    }
}
