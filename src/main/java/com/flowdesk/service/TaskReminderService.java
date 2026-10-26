package com.flowdesk.service;

import com.flowdesk.enums.*;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class TaskReminderService {
    private final TaskReminderMapper reminders;
    private final ProjectMapper projects;
    private final TaskMapper tasks;
    private final NotificationMapper notifications;
    private final boolean enabled;

    public TaskReminderService(TaskReminderMapper reminders, ProjectMapper projects, TaskMapper tasks,
                               NotificationMapper notifications,
                               @Value("${flowdesk.reminders.enabled:false}") boolean enabled) {
        this.reminders = reminders;
        this.projects = projects;
        this.tasks = tasks;
        this.notifications = notifications;
        this.enabled = enabled;
    }

    /** 调用方已持有项目锁，计划与任务变更在同一事务中提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void syncTask(Long taskId) {
        if (!enabled) return;
        Task task = tasks.selectByIdForUpdate(taskId);
        if (task != null && task.getDeadline() != null) {
            reminders.syncPlan(task.getId(), task.getProjectId(), task.getDeadline(), task.getAssigneeId());
        }
    }

    /** 初始化、项目启动和重启恢复均由扫描触发，每个任务独立提交。 */
    @Transactional
    public void planTask(Long taskId, LocalDateTime now) {
        Task observed = tasks.selectById(taskId);
        if (observed == null) return;
        Project project = projects.selectByIdForUpdate(observed.getProjectId());
        Task task = tasks.selectByIdForUpdate(taskId);
        if (project == null || project.getDeletedAt() != null || task == null || task.getDeletedAt() != null
            || !Objects.equals(project.getId(), task.getProjectId()) || task.getDeadline() == null) return;
        reminders.syncPlan(taskId, task.getProjectId(), task.getDeadline(), task.getAssigneeId());
        if (!active(project, task)) return;
        TaskReminderPlan plan = reminders.lockPlan(taskId);
        TaskReminderKind kind = dueKind(task.getDeadline(), now);
        if (kind != null) {
            reminders.createEvent(taskId, task.getProjectId(), plan.revision(), kind.name(), now);
            reminders.reopenCancelled(taskId, plan.revision(), kind.name(), now);
        }
    }

    static TaskReminderKind dueKind(LocalDateTime deadline, LocalDateTime now) {
        if (!deadline.isAfter(now)) return TaskReminderKind.OVERDUE;
        return deadline.isAfter(now.plusHours(24)) ? null : TaskReminderKind.DUE_SOON;
    }

    private boolean active(Project project, Task task) {
        return project != null && project.getDeletedAt() == null && project.getStatus() == ProjectStatus.IN_PROGRESS
            && task != null && task.getDeletedAt() == null && Objects.equals(project.getId(), task.getProjectId())
            && (task.getStatus() == TaskStatus.TODO || task.getStatus() == TaskStatus.IN_PROGRESS);
    }

    /** 通知落库与事件完成共用事务；提交之后调用方才能确认 Redis 消息。 */
    @Transactional
    public void deliver(Long eventId) {
        TaskReminderEvent observed = reminders.findEvent(eventId);
        if (observed == null) return;
        Project project = projects.selectByIdForUpdate(observed.projectId());
        Task task = tasks.selectByIdForUpdate(observed.taskId());
        TaskReminderPlan plan = reminders.lockPlan(observed.taskId());
        TaskReminderEvent event = reminders.lockEvent(eventId);
        LocalDateTime now = LocalDateTime.now();
        if (event == null || !"PENDING".equals(event.state())) return;
        if (!active(project, task) || plan == null || task.getDeadline() == null
            || plan.revision() != event.planRevision() || !Objects.equals(plan.deadline(), task.getDeadline())
            || !Objects.equals(plan.assigneeId(), task.getAssigneeId())) {
            reminders.finish(eventId, "CANCELLED", now);
            return;
        }
        TaskReminderKind kind = TaskReminderKind.valueOf(event.kind());
        if (dueKind(task.getDeadline(), now) != kind) {
            // 过期的临期消息作废，由调度器补建逾期事件；不把历史消息改成另一种通知。
            reminders.finish(eventId, "CANCELLED", now);
            return;
        }
        Long recipient = task.getAssigneeId() == null ? null : reminders.effectiveAssignee(task.getProjectId(), task.getAssigneeId());
        if (recipient == null) recipient = reminders.effectiveManager(task.getProjectId());
        if (recipient == null) throw new IllegalStateException("没有有效的提醒接收人，保留事件等待重试");
        Notification notification = new Notification();
        notification.setRecipientId(recipient);
        notification.setActorId(null);
        notification.setType(kind.notificationType());
        notification.setTitle(kind.title());
        notification.setContent("任务“" + task.getTitle() + "”" + (kind == TaskReminderKind.DUE_SOON ? "将在24小时内到期" : "已超过截止时间")
            + "，截止时间：" + task.getDeadline());
        notification.setProjectId(task.getProjectId());
        notification.setTargetType("TASK");
        notification.setTargetId(task.getId());
        notification.setCreatedAt(now);
        if (notifications.insert(notification) != 1 || reminders.finish(eventId, "DONE", now) != 1) {
            throw new IllegalStateException("提醒写入失败");
        }
    }
}
