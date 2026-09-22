package com.flowdesk;

import com.flowdesk.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
public class JwtUtilTest {

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void testGenerateAndParseToken() {

        String token = jwtUtil.generateToken(1L);

        System.out.println("生成的 Token：");
        System.out.println(token);

        Claims claims = jwtUtil.parseToken(token);

        System.out.println("解析出来的 userId：" + claims.getSubject());
        System.out.println("签发时间：" + claims.getIssuedAt());
        System.out.println("过期时间：" + claims.getExpiration());
    }
}