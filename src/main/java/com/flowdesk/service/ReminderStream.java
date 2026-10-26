package com.flowdesk.service;

import java.util.List;

/** 仅传递事件编号，业务内容和消费结果以数据库为准。 */
public interface ReminderStream {
    record Message(String id, String eventId) {}
    void publish(Long eventId);
    List<Message> read();
    List<Message> reclaim();
    void acknowledge(String messageId);
}
