package com.flowdesk.realtime;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.event.NotificationCreatedEvent;
import com.flowdesk.event.UserDisabledEvent;
import com.flowdesk.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Component
public class NotificationWebSocketHandler extends AbstractWebSocketHandler implements DisposableBean {
    public static final String IDENTITY_ATTRIBUTE = "notificationIdentity";
    private static final Logger log = LoggerFactory.getLogger(NotificationWebSocketHandler.class);
    private final UserMapper users;
    private final ObjectMapper json;
    private final Executor executor;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    private static class Connection {
        final WebSocketSession session;
        final NotificationTicketService.Identity identity;
        volatile long lastPong = System.currentTimeMillis();
        Connection(WebSocketSession session, NotificationTicketService.Identity identity) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 5000, 64 * 1024);
            this.identity = identity;
        }
    }

    public NotificationWebSocketHandler(UserMapper users, ObjectMapper json,
        @Qualifier("notificationPushExecutor") Executor executor) {
        this.users = users;
        this.json = json;
        this.executor = executor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var identity = session.getAttributes().get(IDENTITY_ATTRIBUTE);
        if (!(identity instanceof NotificationTicketService.Identity verified)) {
            closeRaw(session, CloseStatus.POLICY_VIOLATION); return;
        }
        Connection connection = new Connection(session, verified);
        if (!validate(connection)) return;
        connections.put(session.getId(), connection);
        // 再次检查，覆盖建立连接期间账号刚被禁用的情况。
        if (validate(connection)) send(connection, new TextMessage("{\"type\":\"ready\"}"));
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection != null) connection.lastPong = System.currentTimeMillis();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { connections.remove(session.getId()); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Connection connection = connections.get(session.getId());
        if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
        else closeRaw(session, CloseStatus.SERVER_ERROR);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        if (event.notificationId() == null || event.recipientId() == null) return;
        try { executor.execute(() -> push(event)); }
        catch (RuntimeException error) { log.warn("通知推送队列繁忙，通知仍可通过接口查询，notificationId={}", event.notificationId()); }
    }

    private void push(NotificationCreatedEvent event) {
        try {
            String message = json.writeValueAsString(Map.of("type", "notification.created", "notificationId", event.notificationId(),
                "title", event.title() == null ? "收到新通知" : event.title()));
            for (Connection connection : connections.values()) {
                if (connection.identity.userId() == event.recipientId() && validate(connection)) {
                    send(connection, new TextMessage(message));
                }
            }
        } catch (RuntimeException error) { log.warn("实时通知发送失败，notificationId={}", event.notificationId()); }
    }

    @TransactionalEventListener
    public void onUserDisabled(UserDisabledEvent event) {
        for (Connection connection : connections.values()) {
            if (connection.identity.userId() == event.userId()) close(connection, new CloseStatus(4003, "账号已被禁用"));
        }
    }

    @Scheduled(fixedDelayString = "${flowdesk.websocket.check-interval-ms:20000}", scheduler = "notificationSocketScheduler")
    public void checkConnections() {
        Map<Long, Boolean> active = new HashMap<>();
        for (Connection connection : connections.values()) {
            try {
                long now = System.currentTimeMillis();
                if (connection.identity.tokenExpiresAt() <= now) { close(connection, new CloseStatus(4001, "登录已过期")); continue; }
                boolean enabled = active.computeIfAbsent(connection.identity.userId(), this::activeUser);
                if (!enabled) { close(connection, new CloseStatus(4003, "账号不可用")); continue; }
                if (now - connection.lastPong > 60000) { close(connection, CloseStatus.GOING_AWAY); continue; }
                send(connection, new PingMessage());
            } catch (RuntimeException error) { close(connection, CloseStatus.SERVER_ERROR); }
        }
    }

    private boolean validate(Connection connection) {
        if (connection.identity.tokenExpiresAt() <= System.currentTimeMillis()) {
            close(connection, new CloseStatus(4001, "登录已过期")); return false;
        }
        try {
            if (activeUser(connection.identity.userId())) return true;
            close(connection, new CloseStatus(4003, "账号不可用"));
        } catch (RuntimeException error) { close(connection, CloseStatus.SERVER_ERROR); }
        return false;
    }

    private boolean activeUser(long userId) {
        var user = users.selectById(userId);
        return user != null && user.getStatus() == UserStatus.ACTIVE;
    }

    private void send(Connection connection, WebSocketMessage<?> message) {
        try { if (connection.session.isOpen()) connection.session.sendMessage(message); }
        catch (Exception error) { close(connection, CloseStatus.SERVER_ERROR); }
    }

    private void close(Connection connection, CloseStatus status) {
        connections.remove(connection.session.getId(), connection);
        closeRaw(connection.session, status);
    }

    private void closeRaw(WebSocketSession session, CloseStatus status) {
        try { session.close(status); }
        catch (Exception ignored) { /* 断开的连接不影响其他用户。 */ }
    }

    @Override
    public void destroy() {
        for (Connection connection : connections.values()) close(connection, CloseStatus.GOING_AWAY);
    }
}
