package com.flowdesk;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.vo.ProjectTaskSummaryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectTaskSummaryCacheTransactionTest {

    @Autowired
    private ProjectTaskSummaryCache taskSummaryCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long projectId;
    private String key;

    @Value("${flowdesk.test.redis-confirmed:false}")
    private boolean testRedisConfirmed;

    @BeforeEach
    void prepareCache() {
        assertTrue(testRedisConfirmed, "必须明确确认专用 Redis 测试环境后才能写入测试 Key");
        projectId = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, Long.MAX_VALUE);
        key = "flowdesk:project:" + projectId + ":task-summary:v1";

        ProjectTaskSummaryVO summary = new ProjectTaskSummaryVO(projectId, 0L, 0L, 0L, 0L, 0L, 0L, new BigDecimal("0.00"));

        taskSummaryCache.put(projectId, summary);

        assertNotNull(redisTemplate.opsForValue().get(key));
    }

    @AfterEach
    void cleanCache() {
        if (key != null) {
            redisTemplate.delete(key);
        }
    }

    @Test
    void deletesCacheOnlyAfterCommit() {
        taskSummaryCache.evictAfterCommit(projectId);

        // 已登记，但事务尚未提交，缓存必须仍然存在
        assertNotNull(redisTemplate.opsForValue().get(key));

        TestTransaction.flagForCommit();
        TestTransaction.end();

        // 提交后，回调应当已经删除缓存
        assertNull(redisTemplate.opsForValue().get(key));
    }

    @Test
    void keepsCacheAfterRollback() {
        taskSummaryCache.evictAfterCommit(projectId);

        assertNotNull(redisTemplate.opsForValue().get(key));

        TestTransaction.flagForRollback();
        TestTransaction.end();

        // 回滚不能触发 afterCommit，缓存应当保留
        assertNotNull(redisTemplate.opsForValue().get(key));
    }
}
