package com.flowdesk.realtime;

import com.flowdesk.enums.UserStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.util.JwtUtil;
import com.flowdesk.vo.NotificationTicketVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class NotificationTicketService {
    private final JwtUtil jwt;
    private final UserMapper users;
    private final StringRedisTemplate redis;
    private final String prefix;
    private final SecureRandom random = new SecureRandom();

    public NotificationTicketService(JwtUtil jwt, UserMapper users, StringRedisTemplate redis,
        @Value("${flowdesk.websocket.ticket-prefix:flowdesk:ws:ticket:v1:}") String prefix) {
        this.jwt = jwt;
        this.users = users;
        this.redis = redis;
        this.prefix = prefix;
    }

    public record Identity(long userId, long tokenExpiresAt) {}

    public NotificationTicketVO issue(String authorization) {
        Identity identity;
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) throw new IllegalArgumentException();
            var claims = jwt.parseToken(authorization.substring(7));
            identity = new Identity(Long.parseLong(claims.getSubject()), claims.getExpiration().getTime());
        } catch (RuntimeException error) {
            throw new BusinessException(401, "登录已过期或Token无效");
        }
        requireActive(identity);
        long expiresAt = Math.min(System.currentTimeMillis() + 60_000, identity.tokenExpiresAt());
        long ttl = expiresAt - System.currentTimeMillis();
        if (ttl <= 0) throw new BusinessException(401, "登录已过期");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redis.opsForValue().set(prefix + ticket, identity.userId() + ":" + identity.tokenExpiresAt(), Duration.ofMillis(ttl));
        } catch (DataAccessException error) {
            throw new BusinessException(503, "实时通知暂不可用，请稍后重试");
        }
        return new NotificationTicketVO(ticket, expiresAt);
    }

    public Identity consume(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw new BusinessException(401, "连接票据无效");
        String value;
        try {
            // 获取并删除必须是一个原子操作，两个连接不能使用同一张票据。
            value = redis.opsForValue().getAndDelete(prefix + ticket);
        } catch (DataAccessException error) {
            throw new BusinessException(503, "实时通知暂不可用，请稍后重试");
        }
        if (value == null) throw new BusinessException(401, "连接票据已失效");
        Identity identity;
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            identity = new Identity(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (IllegalArgumentException error) {
            throw new BusinessException(401, "连接票据无效");
        }
        requireActive(identity);
        return identity;
    }

    private void requireActive(Identity identity) {
        if (identity.userId() <= 0 || identity.tokenExpiresAt() <= System.currentTimeMillis()) {
            throw new BusinessException(401, "登录已过期");
        }
        var user = users.selectById(identity.userId());
        if (user == null || user.getStatus() != UserStatus.ACTIVE) throw new BusinessException(403, "账号不可用");
    }
}
