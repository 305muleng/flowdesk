package com.flowdesk;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.cache.TaskSummaryCacheReadResult;
import com.flowdesk.dto.TaskStatusCountDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.model.Project;
import com.flowdesk.service.ProjectPermissionService;
import com.flowdesk.service.ProjectTaskSummaryService;
import com.flowdesk.vo.ProjectTaskSummaryVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectTaskSummaryServiceTest {

    private static final Long PROJECT_ID = 1001L;

    @Mock
    private ProjectPermissionService projectPermissionService;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private ProjectTaskSummaryCache taskSummaryCache;

    @InjectMocks
    private ProjectTaskSummaryService service;

    @Test
    void summarizesAllStatusesAndExcludesCancelledFromRate() {
        givenExistingProject();

        when(taskMapper.selectTaskStatusCounts(PROJECT_ID)).thenReturn(
            List.of(row("TODO", 2), row("IN_PROGRESS", 2), row("REVIEW", 1), row("DONE", 4), row("CANCELLED", 1))
        );

        ProjectTaskSummaryVO result = service.getTaskSummary(PROJECT_ID);

        assertAll(
            () -> assertEquals(PROJECT_ID, result.getProjectId()),
            () -> assertEquals(10L, result.getTotalTaskCount()),
            () -> assertEquals(2L, result.getTodoTaskCount()),
            () -> assertEquals(2L, result.getInProgressTaskCount()),
            () -> assertEquals(1L, result.getReviewTaskCount()),
            () -> assertEquals(4L, result.getDoneTaskCount()),
            () -> assertEquals(1L, result.getCancelledTaskCount()),
            () -> assertEquals(new BigDecimal("44.44"), result.getCompletionRate())
        );

        verify(projectPermissionService).requireProjectMember(PROJECT_ID);
        verify(taskSummaryCache).get(PROJECT_ID);
        verify(taskMapper).selectTaskStatusCounts(PROJECT_ID);
        verify(taskSummaryCache).put(PROJECT_ID, result);
    }

    @Test
    void roundsCompletionRateToTwoDecimalPlaces() {
        givenExistingProject();

        when(taskMapper.selectTaskStatusCounts(PROJECT_ID)).thenReturn(List.of(row("TODO", 1), row("DONE", 2)));

        ProjectTaskSummaryVO result = service.getTaskSummary(PROJECT_ID);

        assertEquals(new BigDecimal("66.67"), result.getCompletionRate());
    }

    @Test
    void returnsZerosWhenProjectHasNoTasks() {
        givenExistingProject();

        when(taskMapper.selectTaskStatusCounts(PROJECT_ID)).thenReturn(List.of());

        ProjectTaskSummaryVO result = service.getTaskSummary(PROJECT_ID);

        assertAll(
            () -> assertEquals(0L, result.getTotalTaskCount()),
            () -> assertEquals(0L, result.getTodoTaskCount()),
            () -> assertEquals(0L, result.getInProgressTaskCount()),
            () -> assertEquals(0L, result.getReviewTaskCount()),
            () -> assertEquals(0L, result.getDoneTaskCount()),
            () -> assertEquals(0L, result.getCancelledTaskCount()),
            () -> assertEquals(new BigDecimal("0.00"), result.getCompletionRate())
        );
        verify(taskSummaryCache).put(PROJECT_ID, result);
    }

    @Test
    void returnsZeroRateWhenAllTasksAreCancelled() {
        givenExistingProject();

        when(taskMapper.selectTaskStatusCounts(PROJECT_ID)).thenReturn(List.of(row("CANCELLED", 5)));

        ProjectTaskSummaryVO result = service.getTaskSummary(PROJECT_ID);

        assertAll(
            () -> assertEquals(5L, result.getTotalTaskCount()),
            () -> assertEquals(5L, result.getCancelledTaskCount()),
            () -> assertEquals(new BigDecimal("0.00"), result.getCompletionRate())
        );
    }

    @Test
    void rejectsInvalidProjectIdsBeforeQueryingDependencies() {
        Long[] invalidIds = { null, 0L, -1L };

        for (Long id : invalidIds) {
            BusinessException exception = assertThrows(BusinessException.class, () -> service.getTaskSummary(id));

            assertEquals(400, exception.getCode());
        }

        verifyNoInteractions(projectPermissionService, projectMapper, taskMapper);
    }

    @Test
    void stopsQueryingWhenPermissionIsDenied() {
        doThrow(new BusinessException(403, "你不是该项目的有效成员"))
            .when(projectPermissionService)
            .requireProjectMember(PROJECT_ID);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.getTaskSummary(PROJECT_ID));

        assertEquals(403, exception.getCode());
        verifyNoInteractions(projectMapper, taskMapper);
    }

    @Test
    void rejectsMissingProjectWithoutQueryingTaskStatistics() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.getTaskSummary(PROJECT_ID));

        assertEquals(404, exception.getCode());
        verifyNoInteractions(taskMapper);
    }

    @Test
    void rejectsDeletedProjectWithoutQueryingTaskStatistics() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setDeletedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.getTaskSummary(PROJECT_ID));

        assertEquals(404, exception.getCode());
        verifyNoInteractions(taskMapper);
    }

    @Test
    void returnsCachedSummaryWithoutQueryingTaskCounts() {
        givenExistingProject();

        ProjectTaskSummaryVO cachedSummary = new ProjectTaskSummaryVO(
            PROJECT_ID,
            10L,
            2L,
            2L,
            1L,
            4L,
            1L,
            new BigDecimal("44.44")
        );

        when(taskSummaryCache.get(PROJECT_ID)).thenReturn(new TaskSummaryCacheReadResult(cachedSummary, false));

        ProjectTaskSummaryVO actual = service.getTaskSummary(PROJECT_ID);

        assertSame(cachedSummary, actual);

        verify(projectPermissionService).requireProjectMember(PROJECT_ID);
        verify(projectMapper).selectById(PROJECT_ID);
        verify(taskSummaryCache).get(PROJECT_ID);
        verifyNoInteractions(taskMapper);
        verify(taskSummaryCache, never()).put(anyLong(), any(ProjectTaskSummaryVO.class));
    }

    @Test
    void queriesDatabaseWithoutRefillingWhenRedisReadFails() {
        givenExistingProject();

        when(taskSummaryCache.get(PROJECT_ID)).thenReturn(new TaskSummaryCacheReadResult(null, true));

        when(taskMapper.selectTaskStatusCounts(PROJECT_ID)).thenReturn(List.of(row("TODO", 2)));

        ProjectTaskSummaryVO result = service.getTaskSummary(PROJECT_ID);

        assertEquals(2L, result.getTotalTaskCount());
        assertEquals(2L, result.getTodoTaskCount());

        verify(taskMapper).selectTaskStatusCounts(PROJECT_ID);
        verify(taskSummaryCache, never()).put(anyLong(), any(ProjectTaskSummaryVO.class));
    }

    private void givenExistingProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);

        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);

        when(taskSummaryCache.get(PROJECT_ID)).thenReturn(new TaskSummaryCacheReadResult(null, false));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = { "UNKNOWN", "todo" })
    void invalidAggregateStatusStillFailsInsteadOfBeingIgnoredOrCached(String status) {
        givenExistingProject();
        when(taskMapper.selectTaskStatusCounts(PROJECT_ID)).thenReturn(List.of(row(status, 1)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.getTaskSummary(PROJECT_ID));
        assertEquals(500, error.getCode());
        assertEquals("任务状态数据异常", error.getMessage());
        verify(taskSummaryCache, never()).put(anyLong(), any());
    }

    private TaskStatusCountDTO row(String status, long count) {
        TaskStatusCountDTO row = new TaskStatusCountDTO();
        row.setStatus(status);
        row.setTaskCount(count);
        return row;
    }
}
