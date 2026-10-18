package com.flowdesk;

import com.flowdesk.vo.ProjectTaskSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedisConnectionTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void writesAndReadsTaskSummary() {
        String key = "flowdesk:test:task-summary:" + UUID.randomUUID();

        ProjectTaskSummaryVO original = new ProjectTaskSummaryVO(
                1001L,
                10L,
                2L,
                2L,
                1L,
                4L,
                1L,
                new BigDecimal("44.44")
        );

        try {
            // Java 对象转换为 JSON 字符串
            String json = objectMapper.writeValueAsString(original);

            redisTemplate.opsForValue().set(
                    key,
                    json,
                    Duration.ofSeconds(60)
            );

            // 从 Redis 读取字符串
            String cachedJson = redisTemplate.opsForValue().get(key);
            assertNotNull(cachedJson);

            // JSON 字符串还原为 Java 对象
            ProjectTaskSummaryVO restored = objectMapper.readValue(
                    cachedJson,
                    ProjectTaskSummaryVO.class
            );

            assertEquals(original.getProjectId(), restored.getProjectId());
            assertEquals(original.getTotalTaskCount(), restored.getTotalTaskCount());
            assertEquals(original.getTodoTaskCount(), restored.getTodoTaskCount());
            assertEquals(original.getInProgressTaskCount(), restored.getInProgressTaskCount());
            assertEquals(original.getReviewTaskCount(), restored.getReviewTaskCount());
            assertEquals(original.getDoneTaskCount(), restored.getDoneTaskCount());
            assertEquals(original.getCancelledTaskCount(), restored.getCancelledTaskCount());
            assertEquals(original.getCompletionRate(), restored.getCompletionRate());

            System.out.println("Redis 中的 JSON：" + cachedJson);
            System.out.println("还原后的完成率：" + restored.getCompletionRate());
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void writesAndReadsWithExpiration() {
        String key = "flowdesk:test:redis:" + UUID.randomUUID();
        String expectedValue = "hello-flowdesk";

        try {
            redisTemplate.opsForValue().set(
                    key,
                    expectedValue,
                    Duration.ofSeconds(60)
            );

            String actualValue = redisTemplate.opsForValue().get(key);
            Long remainingSeconds = redisTemplate.getExpire(key);

            assertEquals(expectedValue, actualValue);
            assertNotNull(remainingSeconds);
            assertTrue(remainingSeconds > 0 && remainingSeconds <= 60);

            System.out.println("读取结果：" + actualValue);
            System.out.println("剩余有效期：" + remainingSeconds + " 秒");
        } finally {
            redisTemplate.delete(key);
        }
    }
}