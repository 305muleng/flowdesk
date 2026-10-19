package com.flowdesk;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectTaskSummaryDelayedEvictionTest {

    StringRedisTemplate redis;
    ScheduledExecutorService scheduler;
    ProjectTaskSummaryCache cache;
    TransactionTemplate transaction;
    static final String KEY = "flowdesk:project:42:task-summary:v1";

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        scheduler = mock(ScheduledExecutorService.class);
        cache = new ProjectTaskSummaryCache(redis, new ObjectMapper(), scheduler, 250L);
        transaction = new TransactionTemplate(new StubTransactionManager());
    }

    @Test
    void commitDeletesThenRegistersExactDelayAndCallbackDeletesAgain() {
        transaction.executeWithoutResult(status -> {
            cache.evictAfterCommit(42L);
            verifyNoInteractions(redis, scheduler);
        });
        var callback = ArgumentCaptor.forClass(Runnable.class);
        var order = inOrder(redis, scheduler);
        order.verify(redis).delete(KEY);
        order.verify(scheduler).schedule(callback.capture(), eq(250L), eq(TimeUnit.MILLISECONDS));
        callback.getValue().run();
        verify(redis, times(2)).delete(KEY);
    }

    @Test
    void rollbackNeitherDeletesNorSchedules() {
        transaction.executeWithoutResult(status -> {
            cache.evictAfterCommit(42L);
            status.setRollbackOnly();
        });
        verifyNoInteractions(redis, scheduler);
    }

    @Test
    void schedulerRejectionDoesNotFailCommittedBusinessOperation() {
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenThrow(
            new RejectedExecutionException("test rejection")
        );
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> cache.evictAfterCommit(42L)));
        verify(redis).delete(KEY);
    }

    @Test
    void firstDeleteFailureStillSchedulesAndCallbackFailureIsContained() {
        when(redis.delete(KEY)).thenThrow(new DataAccessResourceFailureException("test Redis unavailable"));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> cache.evictAfterCommit(42L)));
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(callback.capture(), eq(250L), eq(TimeUnit.MILLISECONDS));
        assertDoesNotThrow(() -> callback.getValue().run());
        verify(redis, times(2)).delete(KEY);
    }

    @Test
    void requiresTransactionAndPositiveDelay() {
        assertThrows(IllegalStateException.class, () -> cache.evictAfterCommit(42L));
        assertThrows(IllegalArgumentException.class, () -> new ProjectTaskSummaryCache(redis, new ObjectMapper(), scheduler, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProjectTaskSummaryCache(redis, new ObjectMapper(), scheduler, -1));
        verifyNoInteractions(redis, scheduler);
    }
}
