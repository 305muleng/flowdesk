package com.flowdesk.enums;

public enum TaskReminderKind {
    DUE_SOON("TASK_DUE_SOON", "任务即将到期"),
    OVERDUE("TASK_OVERDUE", "任务已逾期");

    private final String notificationType;
    private final String title;

    TaskReminderKind(String notificationType, String title) {
        this.notificationType = notificationType;
        this.title = title;
    }

    public String notificationType() { return notificationType; }
    public String title() { return title; }
}
