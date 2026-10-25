package com.flowdesk.model;

import java.time.LocalDateTime;

/** 任务提醒计划的版本只随截止时间或执行人变化。 */
public record TaskReminderPlan(Long taskId, Long projectId, LocalDateTime deadline, Long assigneeId, long revision) {}
