package com.flowdesk;

import com.flowdesk.service.RedisReminderStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.connection.stream.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisReminderStreamTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StreamOperations<String, String, String> ops = mock(StreamOperations.class);
    RedisReminderStream stream = new RedisReminderStream(redis);
    String key = "flowdesk:reminders:stream:v1";
    String group = "task-reminders";

    @BeforeEach
    void setup() {
        when(redis.<String, String>opsForStream()).thenReturn(ops);
    }

    @Test
    void publisherSendsOnlyAnEventIdentifierAndBoundsStreamLength() {
        when(ops.add(key, Map.of("eventId", "20"))).thenReturn(RecordId.of("1-0"));
        stream.publish(20L);
        verify(ops).add(key, Map.of("eventId", "20"));
        verify(ops).trim(key, 10000, true);
    }

    @Test
    void nullPublishResultIsNotTreatedAsSuccessfulDelivery() {
        assertThrows(IllegalStateException.class, () -> stream.publish(20L));
    }

    @Test
    void ackPrecedesRemovingTheDedicatedStreamPayload() {
        stream.acknowledge("1-0");
        var order = inOrder(ops);
        order.verify(ops).acknowledge(key, group, "1-0");
        order.verify(ops).delete(key, "1-0");
    }

    @Test
    void failedAckKeepsPayloadForRecovery() {
        when(ops.acknowledge(key, group, "1-0")).thenThrow(new DataAccessResourceFailureException("断开"));
        assertThrows(DataAccessResourceFailureException.class, () -> stream.acknowledge("1-0"));
        verify(ops, never()).delete(anyString(), any(String[].class));
    }

    @Test
    void groupAlreadyExistsIsAcceptedButConnectionErrorsAreNot() {
        when(redis.execute(any(RedisCallback.class))).thenThrow(new DataAccessResourceFailureException("BUSYGROUP Consumer Group name already exists"));
        assertEquals(List.of(), stream.read());
        when(redis.execute(any(RedisCallback.class))).thenThrow(new DataAccessResourceFailureException("连接失败"));
        assertThrows(DataAccessResourceFailureException.class, () -> new RedisReminderStream(redis).read());
    }

    @Test
    void lostConsumerGroupIsRecreatedOnTheNextPoll() {
        when(ops.pending(eq(key), eq(group), any(Range.class), eq(100L)))
            .thenThrow(new DataAccessResourceFailureException("NOGROUP"));
        assertThrows(DataAccessResourceFailureException.class, () -> stream.reclaim());
        assertThrows(DataAccessResourceFailureException.class, () -> stream.reclaim());
        verify(redis, times(2)).execute(any(RedisCallback.class));
    }

    @Test
    void noPendingMessagesMeansThereIsNothingToClaim() {
        when(ops.pending(eq(key), eq(group), any(Range.class), eq(100L))).thenReturn(null);
        assertEquals(List.of(), stream.reclaim());
    }
    @Test
    void onlyIdlePendingMessagesAreClaimedForTheCurrentConsumer() {
        var old = new PendingMessage(RecordId.of("1-0"), Consumer.from(group, "stopped"), java.time.Duration.ofSeconds(60), 1);
        var recent = new PendingMessage(RecordId.of("2-0"), Consumer.from(group, "working"), java.time.Duration.ofSeconds(59), 1);
        when(ops.pending(eq(key), eq(group), any(Range.class), eq(100L)))
            .thenReturn(new PendingMessages(group, List.of(old, recent)));
        when(ops.claim(eq(key), eq(group), anyString(), eq(java.time.Duration.ofSeconds(60)), eq(RecordId.of("1-0"))))
            .thenReturn(List.of(MapRecord.create(key, Map.of("eventId", "20")).withId(RecordId.of("1-0"))));
        var recovered = stream.reclaim();
        assertEquals(1, recovered.size());
        assertEquals("20", recovered.get(0).eventId());
        verify(ops).claim(eq(key), eq(group), anyString(), eq(java.time.Duration.ofSeconds(60)), eq(RecordId.of("1-0")));
    }

    @Test
    void healthyPollingDoesNotRepeatedlyTryToCreateTheGroup() {
        stream.reclaim(); stream.read(); stream.reclaim(); stream.read();
        verify(redis, times(1)).execute(any(RedisCallback.class));
    }

}
