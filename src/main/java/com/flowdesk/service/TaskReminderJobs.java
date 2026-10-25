package com.flowdesk.service;

import com.flowdesk.mapper.TaskReminderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(name = "flowdesk.reminders.enabled", havingValue = "true")
public class TaskReminderJobs {
    private static final Logger log = LoggerFactory.getLogger(TaskReminderJobs.class);
    private final TaskReminderMapper reminders;
    private final TaskReminderService service;
    private final ReminderStream stream;
    private long repairCursor;
    private long dueCursor;

    public TaskReminderJobs(TaskReminderMapper reminders, TaskReminderService service, ReminderStream stream) {
        this.reminders = reminders;
        this.service = service;
        this.stream = stream;
    }

    @Scheduled(fixedDelayString = "${flowdesk.reminders.plan-delay-ms:30000}", scheduler = "taskReminderScheduler")
    public void plan() {
        LocalDateTime now = LocalDateTime.now();
        try {
            List<Long> unplanned = reminders.findUnplanned(repairCursor, 100);
            for (Long taskId : unplanned) planOne(taskId, now);
            repairCursor = unplanned.isEmpty() ? 0 : unplanned.get(unplanned.size() - 1);
            List<Long> due = reminders.findDue(now, now.plusHours(24), dueCursor, 100);
            for (Long taskId : due) planOne(taskId, now);
            dueCursor = due.isEmpty() ? 0 : due.get(due.size() - 1);
        } catch (RuntimeException error) {
            log.warn("扫描提醒计划失败，将在下轮重试", error);
        }
    }

    private void planOne(Long taskId, LocalDateTime now) {
        try { service.planTask(taskId, now); }
        catch (RuntimeException error) { log.warn("生成任务 {} 的提醒计划失败，将重试", taskId, error); }
    }

    @Scheduled(fixedDelayString = "${flowdesk.reminders.publish-delay-ms:5000}", scheduler = "taskReminderScheduler")
    public void publish() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (var event : reminders.findPublishable(now, 100)) {
                // 有上限的退避防止持续故障时忙循环；不因重试次数耗尽而丢弃提醒。
                long retrySeconds = Math.min(300, 5L << Math.min(event.attempts(), 6));
                if (reminders.reservePublish(event.id(), now, now.plusSeconds(retrySeconds)) != 1) continue;
                try { stream.publish(event.id()); }
                catch (RuntimeException error) {
                    failure(event.id(), error);
                    // Redis 整体不可用时及时结束本批，不让每条消息都等待连接超时。
                    break;
                }
            }
        } catch (RuntimeException error) { log.warn("投递提醒事件失败，将重试", error); }
    }

    @Scheduled(fixedDelayString = "${flowdesk.reminders.consume-delay-ms:1000}", scheduler = "taskReminderScheduler")
    public void consume() {
        try {
            process(stream.reclaim());
            process(stream.read());
        } catch (RuntimeException error) { log.warn("读取提醒队列失败，将重试", error); }
    }

    private void process(List<ReminderStream.Message> messages) {
        for (var message : messages) {
            Long eventId;
            try {
                eventId = Long.valueOf(message.eventId());
                if (eventId <= 0) throw new NumberFormatException();
            } catch (RuntimeException malformed) {
                log.warn("丢弃格式错误的提醒消息 {}", message.id());
                stream.acknowledge(message.id());
                continue;
            }
            try {
                service.deliver(eventId);
                // service 是事务代理，返回意味着事务已提交；确认失败会重复消费，由数据库去重。
                stream.acknowledge(message.id());
            } catch (RuntimeException error) { failure(eventId, error); }
        }
    }

    private void failure(Long eventId, RuntimeException error) {
        log.warn("提醒事件 {} 处理失败，保留并重试", eventId, error);
        // 不存储异常原文，避免连接凭据等敏感内容进入业务表。
        try { reminders.recordFailure(eventId, error.getClass().getSimpleName()); }
        catch (RuntimeException recordingError) { log.warn("保存提醒失败状态失败", recordingError); }
    }
}
