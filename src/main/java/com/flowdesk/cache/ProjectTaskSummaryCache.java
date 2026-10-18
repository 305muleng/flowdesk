package com.flowdesk.cache;

import com.flowdesk.vo.ProjectTaskSummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ProjectTaskSummaryCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectTaskSummaryCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final long secondDeleteDelayMs;

    public ProjectTaskSummaryCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("taskSummaryCacheScheduler")
            ScheduledExecutorService scheduler,
            @Value("${flowdesk.cache.task-summary.second-delete-delay-ms:10000}")
            long secondDeleteDelayMs) {
        if (secondDeleteDelayMs <= 0) {
            throw new IllegalArgumentException("延迟补删时间必须大于零");
        }

        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
        this.secondDeleteDelayMs = secondDeleteDelayMs;
    }

    public TaskSummaryCacheReadResult get(Long projectId) {
        String key = "flowdesk:project:" + projectId + ":task-summary:v1";

        try {
            String json = redisTemplate.opsForValue().get(key);

            if (json == null) {
                return new TaskSummaryCacheReadResult(null, false);
            }

            ProjectTaskSummaryVO summary = objectMapper.readValue(
                    json,
                    ProjectTaskSummaryVO.class
            );

            return new TaskSummaryCacheReadResult(summary, false);
        } catch (DataAccessException e) {
            log.warn("读取项目任务统计缓存失败，projectId={}", projectId, e);
            return new TaskSummaryCacheReadResult(null, true);
        } catch (JacksonException e) {
            log.warn("解析项目任务统计缓存失败，projectId={}", projectId, e);
            return new TaskSummaryCacheReadResult(null, false);
        }
    }

    public void put(Long projectId, ProjectTaskSummaryVO summary) {
        String key = "flowdesk:project:" + projectId + ":task-summary:v1";

        try {
            String json = objectMapper.writeValueAsString(summary);

            redisTemplate.opsForValue().set(
                    key,
                    json,
                    Duration.ofSeconds(60)
            );
        } catch (DataAccessException | JacksonException e) {
            log.warn("写入项目任务统计缓存失败，projectId={}", projectId, e);
        }
    }

    private void evict(Long projectId) {
        String key = "flowdesk:project:" + projectId + ":task-summary:v1";

        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.warn("删除项目任务统计缓存失败，projectId={}", projectId, e);
        }
    }

    public void evictAfterCommit(Long projectId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("必须在有效事务中登记缓存删除");
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        evict(projectId);
                        scheduleSecondEviction(projectId);
                    }
                }
        );
    }

    private void scheduleSecondEviction(Long projectId) {
        try {
            scheduler.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            log.info("开始延迟补删项目任务统计缓存，projectId={}", projectId);
                            evict(projectId);
                        }
                    },
                    secondDeleteDelayMs,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException e) {
            log.warn("登记延迟补删任务失败，projectId={}", projectId, e);
        }
    }
}