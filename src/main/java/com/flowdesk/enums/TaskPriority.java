package com.flowdesk.enums;

/** 正式任务的优先级定义，与其他领域的高低等级分开维护。 */
public enum TaskPriority {
    LOW,
    MEDIUM,
    HIGH;

    public String getCode() {
        return name();
    }

    public boolean matches(String code) {
        return name().equals(code);
    }

    public static boolean isKnownCode(String code) {
        for (TaskPriority value : values()) {
            if (value.matches(code)) return true;
        }
        return false;
    }
}
