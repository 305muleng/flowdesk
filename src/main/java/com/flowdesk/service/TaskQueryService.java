package com.flowdesk.service;

import com.flowdesk.common.ListPagination;
import com.flowdesk.common.PageResult;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.enums.SystemRole;
import com.flowdesk.enums.TaskStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.TaskCommentMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskSubmissionMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.Task;
import com.flowdesk.vo.MyTaskSummaryVO;
import com.flowdesk.vo.TaskCommentVO;
import com.flowdesk.vo.TaskSubmissionVO;
import com.flowdesk.vo.TaskVO;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class TaskQueryService {
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final ProjectPermissionService projectPermissionService;
    private final TaskSubmissionMapper taskSubmissionMapper;
    private final TaskCommentMapper taskCommentMapper;

    public TaskQueryService(TaskMapper taskMapper, ProjectMapper projectMapper,
        ProjectPermissionService projectPermissionService, TaskSubmissionMapper taskSubmissionMapper,
        TaskCommentMapper taskCommentMapper) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.projectPermissionService = projectPermissionService;
        this.taskSubmissionMapper = taskSubmissionMapper;
        this.taskCommentMapper = taskCommentMapper;
    }

    public PageResult<TaskVO> getProjectTasks(
        Long projectId,
        String scope,
        String status,
        long page,
        long size,
        Long assigneeId,
        boolean unfinishedOnly
    ) {
        projectPermissionService.requireProjectMember(projectId);
        requireExistingProject(projectId);
        var taskPage = ListPagination.<TaskVO>page(page, size);
        if (assigneeId != null && assigneeId <= 0) {
            throw new BusinessException(400, "assigneeId 必须为正数");
        }

        String normalizedScope = scope.trim().toUpperCase();

        if (!"ALL".equals(normalizedScope) && !"MINE".equals(normalizedScope)) {
            throw new BusinessException(400, "任务查看范围只能是 ALL 或 MINE");
        }

        String normalizedStatus = normalizeTaskStatusFilter(status);

        CurrentUser currentUser = UserContext.get();

        var result = taskMapper.selectProjectTasks(
            taskPage,
            projectId,
            normalizedScope,
            currentUser.getUserId(),
            normalizedStatus,
            assigneeId,
            unfinishedOnly
        );
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    public PageResult<TaskVO> getMyTasks(long page, long size, String status, String keyword, String projectScope) {
        var taskPage = ListPagination.<TaskVO>page(page, size);
        String normalizedStatus = normalizeTaskStatusFilter(status);
        String normalizedProjectScope = projectScope.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("CURRENT", "HISTORY", "ALL", "ACTIONABLE").contains(normalizedProjectScope)) {
            throw new BusinessException(400, "项目范围只能是 CURRENT、HISTORY、ALL 或 ACTIONABLE");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (!SystemRole.USER.matches(UserContext.get().getSystemRole())) {
            return new PageResult<>(List.of(), 0, page, size, 0);
        }
        var result = taskMapper.selectMyTasks(taskPage, UserContext.get().getUserId(), normalizedStatus, normalizedKeyword, normalizedProjectScope);
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    public MyTaskSummaryVO getMyTaskSummary() {
        if (!SystemRole.USER.matches(UserContext.get().getSystemRole())) return MyTaskSummaryVO.empty();
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.selectMyTaskSummary(UserContext.get().getUserId(), now, now.plusDays(3));
    }

    public List<TaskSubmissionVO> getTaskSubmissions(Long taskId) {
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        projectPermissionService.requireProjectMember(task.getProjectId());
        requireExistingProject(task.getProjectId());

        return taskSubmissionMapper.selectTaskSubmissions(taskId);
    }

    private String normalizeTaskStatusFilter(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        // ALL 仅属于查询筛选，不能作为任务状态写入数据库。
        if (!"ALL".equals(normalized)) {
            try {
                TaskStatus.fromCode(normalized);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(400, "任务状态筛选条件不正确");
            }
        }
        return normalized;
    }

    private Project requireExistingProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }
        return project;
    }

    public TaskVO getTaskDetail(Long taskId) {
        // 1. 先找到任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须是这个任务所属项目的成员
        projectPermissionService.requireProjectMember(task.getProjectId());
        requireExistingProject(task.getProjectId());

        // 3. 查询前端需要的详细信息
        TaskVO taskVO = taskMapper.selectTaskDetail(taskId);

        if (taskVO == null) {
            throw new BusinessException(404, "任务不存在");
        }

        return taskVO;
    }

    public List<TaskCommentVO> getTaskComments(Long taskId) {
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        projectPermissionService.requireProjectMember(task.getProjectId());
        requireExistingProject(task.getProjectId());

        return taskCommentMapper.selectTaskComments(taskId);
    }
}
