package com.flowdesk.event;

public record NotificationCreatedEvent(Long notificationId, Long recipientId, String title) {}
