package com.flowdesk;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.event.NotificationCreatedEvent;
import com.flowdesk.event.UserDisabledEvent;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.realtime.NotificationTicketService.Identity;
import com.flowdesk.realtime.NotificationWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationWebSocketHandlerTest {
    UserMapper users=mock(UserMapper.class);
    NotificationWebSocketHandler handler=new NotificationWebSocketHandler(users,new ObjectMapper(),Runnable::run);
    WebSocketSession connection(String id,long userId) throws Exception {
        var user=new User();user.setId(userId);user.setStatus(UserStatus.ACTIVE);
        when(users.selectById(userId)).thenReturn(user);
        var socket=mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(Map.of(NotificationWebSocketHandler.IDENTITY_ATTRIBUTE,new Identity(userId,System.currentTimeMillis()+60000)));
        handler.afterConnectionEstablished(socket);
        clearInvocations(socket);
        return socket;
    }
    @Test void pushesToAllRecipientConnectionsAndNoOtherUsers() throws Exception {
        var first=connection("a",2);var second=connection("b",2);var other=connection("c",3);
        handler.onNotificationCreated(new NotificationCreatedEvent(10L,2L,"任务分配"));
        verify(first).sendMessage(argThat(m->m instanceof TextMessage && m.getPayload().toString().contains("notification.created")));
        verify(second).sendMessage(any(TextMessage.class));
        verify(other,never()).sendMessage(any());
        handler.destroy();
    }
    @Test void expiredAndDisabledConnectionsStopReceiving() throws Exception {
        var socket=connection("a",2);
        handler.onUserDisabled(new UserDisabledEvent(2L,"用户",1L));
        verify(socket).close(argThat(s->s.getCode()==4003));
        handler.onNotificationCreated(new NotificationCreatedEvent(10L,2L,"任务分配"));
        verify(socket,never()).sendMessage(any());
    }
    @Test void failedPushQueueCannotFailCommittedBusiness() throws Exception {
        var rejecting=new NotificationWebSocketHandler(users,new ObjectMapper(),task->{throw new RejectedExecutionException();});
        assertDoesNotThrow(()->rejecting.onNotificationCreated(new NotificationCreatedEvent(10L,2L,"任务分配")));
    }
}
