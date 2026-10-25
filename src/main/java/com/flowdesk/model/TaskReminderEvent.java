package com.flowdesk.model;

/** 持久化待发送事件也是消费去重的依据，不依赖 Redis 保留全部消息。 */
public record TaskReminderEvent(Long id, Long taskId, Long projectId, long planRevision,
                                String kind, String state, int attempts) {}
