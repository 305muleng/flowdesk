package com.flowdesk;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.realtime.NotificationTicketService;
import com.flowdesk.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationTicketServiceTest {
    UserMapper users = mock(UserMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
    JwtUtil jwt = new JwtUtil("flowdesk-test-secret-key-2026-only-for-testing", 7200000);
    NotificationTicketService service = new NotificationTicketService(jwt, users, redis, "test:ticket:");
    User user;
    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        user = new User(); user.setId(2L); user.setStatus(UserStatus.ACTIVE);
        lenient().when(users.selectById(2L)).thenReturn(user);
    }
    @Test void ticketHasShortLifetimeAndDoesNotContainJwt() {
        String token = jwt.generateToken(2L);
        var result = service.issue("Bearer " + token);
        assertTrue(result.ticket().matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(token, result.ticket());
        verify(values).set(eq("test:ticket:" + result.ticket()), startsWith("2:"), argThat((Duration ttl)->!ttl.isNegative() && ttl.toMillis()<=60000));
    }
    @Test void consumeUsesAtomicGetAndDeleteAndRejectsReplay() {
        String ticket = "a".repeat(43);
        when(values.getAndDelete("test:ticket:" + ticket)).thenReturn("2:" + (System.currentTimeMillis()+100000), null);
        assertEquals(2, service.consume(ticket).userId());
        assertEquals(401, assertThrows(BusinessException.class, ()->service.consume(ticket)).getCode());
        verify(values,times(2)).getAndDelete("test:ticket:" + ticket);
        verify(values,never()).get(anyString());
    }
    @Test void disabledUserCannotConsumePreviouslyIssuedTicket() {
        String ticket = "a".repeat(43);
        when(values.getAndDelete(anyString())).thenReturn("2:"+(System.currentTimeMillis()+100000));
        user.setStatus(UserStatus.DISABLED);
        assertEquals(403, assertThrows(BusinessException.class,()->service.consume(ticket)).getCode());
    }
    @Test void redisFailureIsReportedAsTemporaryAndNotAsExpiredLogin() {
        doThrow(new DataAccessResourceFailureException("test")).when(values).set(anyString(),anyString(),any(Duration.class));
        assertEquals(503, assertThrows(BusinessException.class,()->service.issue("Bearer "+jwt.generateToken(2L))).getCode());
    }
    @Test void malformedOrExpiredIdentityCannotConnect() {
        assertEquals(401,assertThrows(BusinessException.class,()->service.consume("wrong")).getCode());
        when(values.getAndDelete(anyString())).thenReturn("2:1");
        assertEquals(401,assertThrows(BusinessException.class,()->service.consume("a".repeat(43))).getCode());
        assertEquals(401,assertThrows(BusinessException.class,()->service.issue("Bearer invalid")).getCode());
    }
}
