package com.flowdesk.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "flowdesk.reminders.enabled", havingValue = "true")
public class RedisReminderStream implements ReminderStream {
    static final String KEY = "flowdesk:reminders:stream:v1";
    static final String GROUP = "task-reminders";
    private final String streamKey;
    private final String consumer = UUID.randomUUID().toString();
    private final StringRedisTemplate redis;
    private boolean groupReady;

    public RedisReminderStream(StringRedisTemplate redis) { this(redis, KEY); }

    @Autowired
    public RedisReminderStream(StringRedisTemplate redis,
        @Value("${flowdesk.reminders.stream-key:flowdesk:reminders:stream:v1}") String streamKey) {
        if (streamKey == null || streamKey.isBlank()) throw new IllegalArgumentException("提醒流名称不能为空");
        this.redis = redis;
        this.streamKey = streamKey;
    }

    private StreamOperations<String, String, String> ops() { return redis.opsForStream(); }

    private void ensureGroup() {
        if (groupReady) return;
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                connection.streamCommands().xGroupCreate(streamKey.getBytes(StandardCharsets.UTF_8), GROUP, ReadOffset.from("0-0"), true));
        } catch (org.springframework.dao.DataAccessException error) {
            // 多实例或重启时组已存在是预期情况，其他错误必须交给重试流程。
            Throwable cause = error;
            boolean exists = false;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) exists = true;
                cause = cause.getCause();
            }
            if (!exists) throw error;
        }
        groupReady = true;
    }

    @Override
    public void publish(Long eventId) {
        if (ops().add(streamKey, Map.of("eventId", eventId.toString())) == null) {
            throw new IllegalStateException("Redis 未返回提醒消息编号");
        }
        // 这是可重建的传输队列；数据库待发送事件负责恢复被裁剪或丢失的消息。
        ops().trim(streamKey, 10000, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Message> read() {
        ensureGroup();
        try {
            return messages(ops().read(Consumer.from(GROUP, consumer), StreamReadOptions.empty().count(100),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())));
        } catch (org.springframework.dao.DataAccessException error) {
            // Redis 重启或流被清空后允许重新建组；下一轮由持久化事件补发。
            groupReady = false;
            throw error;
        }
    }

    @Override
    public List<Message> reclaim() {
        ensureGroup();
        PendingMessages pending;
        try { pending = ops().pending(streamKey, GROUP, Range.unbounded(), 100); }
        catch (org.springframework.dao.DataAccessException error) {
            groupReady = false;
            throw error;
        }
        if (pending == null || pending.isEmpty()) return List.of();
        List<RecordId> expired = pending.stream().filter(m -> m.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofSeconds(60)) >= 0)
            .map(PendingMessage::getId).toList();
        if (expired.isEmpty()) return List.of();
        return messages(ops().claim(streamKey, GROUP, consumer, Duration.ofSeconds(60), expired.toArray(RecordId[]::new)));
    }

    @Override
    public void acknowledge(String messageId) {
        ops().acknowledge(streamKey, GROUP, messageId);
        // 专用流只有一个消费组；确认后删正文，数据库仍保留业务处理结果。
        ops().delete(streamKey, messageId);
    }

    private List<Message> messages(List<MapRecord<String, String, String>> records) {
        if (records == null) return List.of();
        return records.stream().map(r -> new Message(r.getId().getValue(), r.getValue().get("eventId"))).toList();
    }
}
