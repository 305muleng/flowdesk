package com.flowdesk;

import com.flowdesk.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// JWT signing/parsing has no dependency on the Spring context, MySQL or Redis.
class JwtUtilTest {

    @Test
    void testGenerateAndParseToken() {
        JwtUtil jwtUtil = new JwtUtil("flowdesk-test-secret-key-2026-only-for-testing", 7_200_000L);
        String token = jwtUtil.generateToken(1L);
        Claims claims = jwtUtil.parseToken(token);

        assertNotNull(token);
        assertEquals("1", claims.getSubject());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertEquals(7_200_000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }
}
