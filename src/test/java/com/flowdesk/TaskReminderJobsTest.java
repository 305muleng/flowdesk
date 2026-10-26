package com.flowdesk;

import com.flowdesk.mapper.TaskReminderMapper;
import com.flowdesk.model.TaskReminderEvent;
import com.flowdesk.service.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;

class TaskReminderJobsTest {
    TaskReminderMapper mapper = mock(TaskReminderMapper.class);
    TaskReminderService service = mock(TaskReminderService.class);
    ReminderStream stream = mock(ReminderStream.class);
    TaskReminderJobs jobs = new TaskReminderJobs(mapper, service, stream);
    TaskReminderEvent event = new TaskReminderEvent(20L, 10L, 4L, 1, "DUE_SOON", "PENDING", 0);

    @Test
    void reserveIsCommittedBeforePublishingAndLostMessagesRemainEligibleForRetry() {
        when(mapper.findPublishable(any(), eq(100))).thenReturn(List.of(event));
        when(mapper.reservePublish(eq(20L), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("断开")).doNothing().when(stream).publish(20L);
        jobs.publish(); jobs.publish();
        var order = inOrder(mapper, stream);
        order.verify(mapper).findPublishable(any(), eq(100));
        order.verify(mapper).reservePublish(eq(20L), any(), any());
        order.verify(stream).publish(20L);
        verify(mapper).recordFailure(20L, "IllegalStateException");
        verify(stream, times(2)).publish(20L);
        verify(mapper, never()).finish(anyLong(), anyString(), any());
    }

    @Test
    void competingPublisherDoesNotRepublishReservedEvent() {
        when(mapper.findPublishable(any(), eq(100))).thenReturn(List.of(event));
        jobs.publish();
        verifyNoInteractions(stream);
    }

    @Test
    void ackOnlyFollowsSuccessfulTransactionAndCommitFailureKeepsMessagePending() {
        when(stream.reclaim()).thenReturn(List.of(new ReminderStream.Message("1-0", "20")));
        when(stream.read()).thenReturn(List.of());
        doThrow(new IllegalStateException("提交失败")).doNothing().when(service).deliver(20L);
        jobs.consume();
        verify(stream, never()).acknowledge(anyString());
        jobs.consume();
        var order = inOrder(service, stream);
        order.verify(service, times(2)).deliver(20L);
        order.verify(stream).acknowledge("1-0");
    }

    @Test
    void failedAckCanBeDeliveredAgainWithoutDiscardingDatabaseState() {
        when(stream.reclaim()).thenReturn(List.of());
        when(stream.read()).thenReturn(List.of(new ReminderStream.Message("2-0", "20")));
        doThrow(new IllegalStateException("确认中断")).doNothing().when(stream).acknowledge("2-0");
        jobs.consume(); jobs.consume();
        verify(service, times(2)).deliver(20L);
        verify(stream, times(2)).acknowledge("2-0");
    }

    @Test
    void malformedMessageDoesNotBlockFollowingValidMessage() {
        when(stream.reclaim()).thenReturn(List.of());
        when(stream.read()).thenReturn(List.of(new ReminderStream.Message("1-0", "bad"),
            new ReminderStream.Message("2-0", "20")));
        jobs.consume();
        verify(stream).acknowledge("1-0");
        verify(service).deliver(20L);
        verify(stream).acknowledge("2-0");
    }

    @Test
    void oneTaskFailureDoesNotAbortTheRemainingPlanningBatch() {
        when(mapper.findUnplanned(0, 100)).thenReturn(List.of(10L, 11L));
        when(mapper.findDue(any(), any(), eq(0L), eq(100))).thenReturn(List.of(12L));
        doThrow(new IllegalStateException()).when(service).planTask(eq(10L), any());
        jobs.plan();
        verify(service).planTask(eq(11L), any());
        verify(service).planTask(eq(12L), any());
    }

    @Test
    void retriesHaveACappedDelayWithoutMarkingEventsCompleted() {
        var retry = new TaskReminderEvent(20L, 10L, 4L, 1, "OVERDUE", "PENDING", 100);
        when(mapper.findPublishable(any(), eq(100))).thenReturn(List.of(retry));
        var now = ArgumentCaptor.forClass(LocalDateTime.class);
        var next = ArgumentCaptor.forClass(LocalDateTime.class);
        jobs.publish();
        verify(mapper).reservePublish(eq(20L), now.capture(), next.capture());
        assertEquals(300, java.time.Duration.between(now.getValue(), next.getValue()).toSeconds());
    }
    @Test
    void planningCursorAdvancesPastFailuresAndWrapsForLaterRetries() {
        when(mapper.findUnplanned(0, 100)).thenReturn(List.of(10L, 11L));
        when(mapper.findUnplanned(11, 100)).thenReturn(List.of());
        doThrow(new IllegalStateException()).when(service).planTask(eq(10L), any());
        jobs.plan(); jobs.plan(); jobs.plan();
        verify(mapper).findUnplanned(11, 100);
        verify(service, times(2)).planTask(eq(11L), any());
        verify(service, times(2)).planTask(eq(10L), any());
    }

}
