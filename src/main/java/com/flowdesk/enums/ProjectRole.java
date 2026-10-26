package com.flowdesk.enums;

/** 项目角色；账号的系统身份由 SystemRole 表达。 */
public enum ProjectRole {
    PROJECT_MANAGER,
    DEVELOPER;

    public String getCode() {
        return name();
    }

    /** 精确匹配现有字符串代码，空值及未知值均不匹配。 */
    public boolean matches(String code) {
        return name().equals(code);
    }

    public static boolean isKnownCode(String code) {
        for (ProjectRole role : values()) {
            if (role.matches(code)) return true;
        }
        return false;
    }
}
