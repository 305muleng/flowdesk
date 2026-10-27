package com.flowdesk.config;

import com.flowdesk.exception.BusinessException;
import com.flowdesk.realtime.NotificationTicketService;
import com.flowdesk.realtime.NotificationWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.Arrays;
import java.util.Map;

@Configuration
@EnableWebSocket
@EnableScheduling
public class NotificationWebSocketConfig implements WebSocketConfigurer {
    private final NotificationWebSocketHandler handler;
    private final NotificationTicketService tickets;
    private final String[] origins;

    public NotificationWebSocketConfig(NotificationWebSocketHandler handler, NotificationTicketService tickets,
        @Value("${flowdesk.websocket.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:8088,http://127.0.0.1:8088}") String origins) {
        this.handler = handler;
        this.tickets = tickets;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/notifications").setAllowedOrigins(origins).addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler ws, Map<String, Object> attributes) {
                if (!Arrays.asList(origins).contains(request.getHeaders().getOrigin())) {
                    response.setStatusCode(HttpStatusCode.valueOf(403)); return false;
                }
                var values = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().get("ticket");
                if (values == null || values.size() != 1) { response.setStatusCode(HttpStatusCode.valueOf(401)); return false; }
                try {
                    attributes.put(NotificationWebSocketHandler.IDENTITY_ATTRIBUTE, tickets.consume(values.get(0)));
                    return true;
                } catch (BusinessException error) {
                    response.setStatusCode(HttpStatusCode.valueOf(error.getCode())); return false;
                }
            }
            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler ws, Exception error) {}
        });
    }

    @Bean
    public static ThreadPoolTaskExecutor notificationPushExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notification-push-");
        return executor;
    }

    @Bean
    public static ThreadPoolTaskScheduler notificationSocketScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-socket-");
        return scheduler;
    }
}
