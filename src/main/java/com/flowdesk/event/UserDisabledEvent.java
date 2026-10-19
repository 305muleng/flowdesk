package com.flowdesk.event;

public record UserDisabledEvent(Long userId, String userName, Long actorId) {}
