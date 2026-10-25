package com.flowdesk.enums;

/** 系统角色；项目内的身份由 ProjectRole 表达。 */
public enum SystemRole {
    USER("USER"),
    SYSTEM_ADMIN("SYSTEM_ADMIN");

    private final String code;

    SystemRole(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 精确匹配现有字符串代码，空值及未知值均不匹配。 */
    public boolean matches(String code) {
        return this.code.equals(code);
    }

    public static boolean isKnownCode(String code) {
        for (SystemRole role : values()) {
            if (role.matches(code)) return true;
        }
        return false;
    }
}
