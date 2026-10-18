package com.flowdesk.service;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.cache.TaskSummaryCacheReadResult;
import com.flowdesk.dto.TaskStatusCountDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.model.Project;
import com.flowdesk.vo.ProjectTaskSummaryVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class ProjectTaskSummaryService {

    private final ProjectPermissionService projectPermissionService;
    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final ProjectTaskSummaryCache taskSummaryCache;

    public ProjectTaskSummaryService(
            ProjectPermissionService projectPermissionService,
            ProjectMapper projectMapper,
            TaskMapper taskMapper,
            ProjectTaskSummaryCache taskSummaryCache) {
        this.projectPermissionService = projectPermissionService;
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.taskSummaryCache = taskSummaryCache;
    }

    public ProjectTaskSummaryVO getTaskSummary(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BusinessException(400, "项目ID不合法");
        }

        projectPermissionService.requireProjectMember(projectId);

        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        TaskSummaryCacheReadResult cacheResult = taskSummaryCache.get(projectId);

        ProjectTaskSummaryVO cachedSummary = cacheResult.getSummary();

        if (cachedSummary != null) {
            return cachedSummary;
        }

        List<TaskStatusCountDTO> rows = taskMapper.selectTaskStatusCounts(projectId);

        long totalTaskCount = 0L;
        long todoTaskCount = 0L;
        long inProgressTaskCount = 0L;
        long reviewTaskCount = 0L;
        long doneTaskCount = 0L;
        long cancelledTaskCount = 0L;

        for (TaskStatusCountDTO row : rows) {
            String status = row.getStatus();
            long taskCount = row.getTaskCount();

            if ("TODO".equals(status)) {
                todoTaskCount += taskCount;
            } else if ("IN_PROGRESS".equals(status)) {
                inProgressTaskCount += taskCount;
            } else if ("REVIEW".equals(status)) {
                reviewTaskCount += taskCount;
            } else if ("DONE".equals(status)) {
                doneTaskCount += taskCount;
            } else if ("CANCELLED".equals(status)) {
                cancelledTaskCount += taskCount;
            } else {
                throw new BusinessException(500, "任务状态数据异常");
            }

            totalTaskCount += taskCount;
        }

        long effectiveTaskCount =
                totalTaskCount - cancelledTaskCount;

        BigDecimal completionRate = effectiveTaskCount == 0
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(doneTaskCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        BigDecimal.valueOf(effectiveTaskCount),
                        2,
                        RoundingMode.HALF_UP
                );

        ProjectTaskSummaryVO summary = new ProjectTaskSummaryVO(
                projectId,
                totalTaskCount,
                todoTaskCount,
                inProgressTaskCount,
                reviewTaskCount,
                doneTaskCount,
                cancelledTaskCount,
                completionRate
        );

        if (!cacheResult.isRedisReadFailed()) {
            taskSummaryCache.put(projectId, summary);
        }

        return summary;
    }
}