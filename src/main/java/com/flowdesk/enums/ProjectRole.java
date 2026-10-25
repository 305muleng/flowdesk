package com.flowdesk.enums;

/** 项目角色；账号的系统身份由 SystemRole 表达。 */
public enum ProjectRole {
    PROJECT_MANAGER("PROJECT_MANAGER"),
    DEVELOPER("DEVELOPER");

    private final String code;

    ProjectRole(String code) {
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
        for (ProjectRole role : values()) {
            if (role.matches(code)) return true;
        }
        return false;
    }
}
