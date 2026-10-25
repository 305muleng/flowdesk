package com.flowdesk.enums;

/** 正式任务的优先级定义，与其他领域的高低等级分开维护。 */
public enum TaskPriority {
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH");

    private final String code;

    TaskPriority(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean matches(String code) {
        return this.code.equals(code);
    }

    public static boolean isKnownCode(String code) {
        for (TaskPriority value : values()) {
            if (value.matches(code)) return true;
        }
        return false;
    }
}
