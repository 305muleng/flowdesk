package com.flowdesk.service;

import com.flowdesk.enums.ProjectMemberStatus;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.enums.TaskStatus;
import com.flowdesk.enums.TaskSubmissionStatus;
import com.flowdesk.enums.UserStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.common.ListPagination;
import com.flowdesk.common.PageResult;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.vo.TaskCommentVO;
import com.flowdesk.vo.TaskSubmissionVO;
import com.flowdesk.vo.TaskVO;
import com.flowdesk.vo.MyTaskSummaryVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectPermissionService projectPermissionService;
    private final TaskSubmissionMapper taskSubmissionMapper;
    private final TaskCommentMapper taskCommentMapper;
    private final OperationLogService operationLogService;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final ProjectTaskSummaryCache taskSummaryCache;

    public TaskService(
        TaskMapper taskMapper,
        ProjectMapper projectMapper,
        ProjectMemberMapper projectMemberMapper,
        ProjectPermissionService projectPermissionService,
        TaskSubmissionMapper taskSubmissionMapper,
        TaskCommentMapper taskCommentMapper,
        OperationLogService operationLogService,
        NotificationService notificationService,
        UserMapper userMapper,
        ProjectTaskSummaryCache taskSummaryCache
    ) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectPermissionService = projectPermissionService;
        this.taskSubmissionMapper = taskSubmissionMapper;
        this.taskCommentMapper = taskCommentMapper;
        this.operationLogService = operationLogService;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.taskSummaryCache = taskSummaryCache;
    }

    @Transactional
    public Long createTask(Long projectId, CreateTaskDTO dto) {
        CurrentUser currentUser = UserContext.get();

        // 先获取项目锁
        Project project = projectMapper.selectByIdForUpdate(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        // 加锁后，重新确认当前用户仍是有效项目负责人
        projectPermissionService.requireProjectManagerForUpdate(projectId);

        if (!ProjectStatus.PREPARING.equals(project.getStatus()) && !ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "当前项目状态不允许创建任务");
        }

        // 3. 截止时间必须在未来
        LocalDateTime now = LocalDateTime.now();

        if (!dto.getDeadline().isAfter(now)) {
            throw new BusinessException(400, "任务截止时间必须晚于当前时间");
        }

        // 4. 检查优先级
        String priority = dto.getPriority();

        if (priority == null || priority.isBlank()) {
            priority = "MEDIUM";
        } else {
            priority = priority.trim().toUpperCase();

            Set<String> allowedPriorities = Set.of("LOW", "MEDIUM", "HIGH");

            if (!allowedPriorities.contains(priority)) {
                throw new BusinessException(400, "任务优先级只能是 LOW、MEDIUM 或 HIGH");
            }
        }

        // 5. 如果指定负责人，负责人必须是项目 ACTIVE 成员
        if (dto.getAssigneeId() != null) {
            ProjectMember assignee = projectMemberMapper.selectMemberForUpdate(projectId, dto.getAssigneeId());

            if (
                assignee == null ||
                !ProjectMemberStatus.ACTIVE.equals(assignee.getStatus()) ||
                !isActiveOrdinaryUser(dto.getAssigneeId())
            ) {
                throw new BusinessException(409, "任务负责人不是该项目的有效开发成员");
            }
        }

        // 6. 创建任务
        Task task = new Task();

        task.setProjectId(projectId);
        task.setCreatorId(currentUser.getUserId());
        task.setAssigneeId(dto.getAssigneeId());

        task.setTitle(dto.getTitle().trim());
        task.setDescription(dto.getDescription());
        task.setGoal(dto.getGoal());

        task.setStatus(TaskStatus.TODO);
        task.setPriority(priority);
        task.setDeadline(dto.getDeadline());

        taskMapper.insert(task);

        Map<String, Object> afterData = new HashMap<>();

        afterData.put("title", task.getTitle());
        afterData.put("assigneeId", task.getAssigneeId());
        afterData.put("status", task.getStatus() == null ? null : task.getStatus().getCode());
        afterData.put("priority", task.getPriority());
        afterData.put("deadline", task.getDeadline());

        operationLogService.record(
            task.getProjectId(),
            currentUser.getUserId(),
            "TASK",
            task.getId(),
            "CREATE_TASK",
            "创建任务",
            null,
            afterData
        );

        if (task.getAssigneeId() != null && !task.getAssigneeId().equals(currentUser.getUserId())) {
            CreateNotificationDTO notificationDTO = new CreateNotificationDTO();

            notificationDTO.setRecipientId(task.getAssigneeId());
            notificationDTO.setActorId(currentUser.getUserId());
            notificationDTO.setType("TASK_ASSIGNED");
            notificationDTO.setTitle("任务分配");
            notificationDTO.setContent("你被分配了任务：" + task.getTitle());
            notificationDTO.setProjectId(task.getProjectId());
            notificationDTO.setTargetType("TASK");
            notificationDTO.setTargetId(task.getId());

            notificationService.createNotification(notificationDTO);
        }
        taskSummaryCache.evictAfterCommit(projectId);

        return task.getId();
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

    public PageResult<TaskVO> getMyTasks(long page, long size, String status, String keyword) {
        var taskPage = ListPagination.<TaskVO>page(page, size);
        String normalizedStatus = normalizeTaskStatusFilter(status);
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (!"USER".equals(UserContext.get().getSystemRole())) {
            return new PageResult<>(List.of(), 0, page, size, 0);
        }
        var result = taskMapper.selectMyTasks(taskPage, UserContext.get().getUserId(), normalizedStatus, normalizedKeyword);
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    public MyTaskSummaryVO getMyTaskSummary() {
        if (!"USER".equals(UserContext.get().getSystemRole())) return MyTaskSummaryVO.empty();
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.selectMyTaskSummary(UserContext.get().getUserId(), now, now.plusDays(3));
    }

    @Transactional
    public void startTask(Long taskId) {
        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须仍然是项目有效成员
        projectPermissionService.requireProjectMember(task.getProjectId());

        CurrentUser currentUser = UserContext.get();

        // 3. 锁定项目后检查状态，与取消项目协调，锁持有到事务结束
        Project project = projectMapper.selectByIdForUpdate(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "项目未处于进行中，不能开始任务");
        }

        // 4. 任务必须已经分配负责人
        if (task.getAssigneeId() == null) {
            throw new BusinessException(409, "任务尚未分配负责人");
        }

        // 5. 只有任务负责人本人才能开始
        if (!currentUser.getUserId().equals(task.getAssigneeId())) {
            throw new BusinessException(403, "只有任务负责人可以开始该任务");
        }

        // 6. 必须从 TODO 状态开始
        if (!TaskStatus.TODO.equals(task.getStatus())) {
            throw new BusinessException(409, "只有待处理任务可以开始");
        }

        String oldStatus = task.getStatus() == null ? null : task.getStatus().getCode();

        // 7. 修改任务状态
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.transitionAssigneeTaskIfCurrent(
            taskId,
            currentUser.getUserId(),
            TaskStatus.TODO,
            TaskStatus.IN_PROGRESS,
            now
        );
        if (updated != 1) {
            throw new BusinessException(409, "任务状态或负责人已发生变化，请刷新后重试");
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setUpdatedAt(now);

        Map<String, Object> beforeData = new HashMap<>();
        beforeData.put("status", oldStatus);

        Map<String, Object> afterData = new HashMap<>();
        afterData.put("status", task.getStatus() == null ? null : task.getStatus().getCode());

        operationLogService.record(
            task.getProjectId(),
            currentUser.getUserId(),
            "TASK",
            task.getId(),
            "START_TASK",
            "开始任务",
            beforeData,
            afterData
        );
        taskSummaryCache.evictAfterCommit(task.getProjectId());
    }

    @Transactional
    public Long submitTask(Long taskId, SubmitTaskDTO dto) {
        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须仍然属于项目
        projectPermissionService.requireProjectMember(task.getProjectId());

        CurrentUser currentUser = UserContext.get();

        // 3. 只有任务负责人本人才能提交
        if (task.getAssigneeId() == null || !currentUser.getUserId().equals(task.getAssigneeId())) {
            throw new BusinessException(403, "只有任务负责人可以提交该任务");
        }

        // 4. 项目必须正在进行
        Project project = projectMapper.selectByIdForUpdate(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "项目未处于进行中，不能提交任务");
        }

        // 5. 只有进行中的任务才能提交审核
        if (!TaskStatus.IN_PROGRESS.equals(task.getStatus())) {
            throw new BusinessException(409, "只有进行中的任务可以提交审核");
        }

        if (dto.getResultUrl() != null && !dto.getResultUrl().isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(dto.getResultUrl().trim());
                if (
                    !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
                    uri.getHost() == null
                ) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "成果链接只允许有效的 http 或 https 地址");
            }
        }
        // 保存任务修改前的状态
        String oldStatus = task.getStatus() == null ? null : task.getStatus().getCode();

        LocalDateTime now = LocalDateTime.now();

        int transitioned = taskMapper.transitionAssigneeTaskIfCurrent(
            taskId,
            currentUser.getUserId(),
            TaskStatus.IN_PROGRESS,
            TaskStatus.REVIEW,
            now
        );
        if (transitioned != 1) {
            throw new BusinessException(409, "任务状态或负责人已发生变化，请刷新后重试");
        }

        // 6. 计算这是第几次提交
        Integer maxSubmissionNo = taskSubmissionMapper.selectMaxSubmissionNo(taskId);

        int submissionNo = maxSubmissionNo + 1;

        // 7. 保存提交记录
        TaskSubmission submission = new TaskSubmission();

        submission.setTaskId(taskId);
        submission.setSubmitterId(currentUser.getUserId());
        submission.setSubmissionNo(submissionNo);

        submission.setCompletionNote(dto.getCompletionNote().trim());
        submission.setResultUrl(dto.getResultUrl() == null ? null : dto.getResultUrl().trim());
        submission.setTestNote(dto.getTestNote().trim());

        submission.setReviewStatus(TaskSubmissionStatus.PENDING);
        submission.setSubmittedAt(now);

        taskSubmissionMapper.insert(submission);

        // 8. 任务进入待审核状态
        task.setStatus(TaskStatus.REVIEW);
        task.setUpdatedAt(now);

        // 9. 记录提交任务的操作日志
        Map<String, Object> beforeData = new HashMap<>();
        beforeData.put("status", oldStatus);

        Map<String, Object> afterData = new HashMap<>();
        afterData.put("status", task.getStatus() == null ? null : task.getStatus().getCode());
        afterData.put("submissionId", submission.getId());
        afterData.put("submissionNo", submission.getSubmissionNo());

        operationLogService.record(
            task.getProjectId(),
            currentUser.getUserId(),
            "TASK",
            task.getId(),
            "SUBMIT_TASK",
            "提交任务成果",
            beforeData,
            afterData
        );

        List<ProjectMember> managers = projectMemberMapper.selectList(
            new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, task.getProjectId())
                .eq(ProjectMember::getRole, "PROJECT_MANAGER")
                .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE)
        );

        for (ProjectMember manager : managers) {
            if (!isActiveOrdinaryUser(manager.getUserId())) {
                continue;
            }

            // 不给自己发通知
            if (manager.getUserId().equals(currentUser.getUserId())) {
                continue;
            }

            CreateNotificationDTO notificationDTO = new CreateNotificationDTO();

            notificationDTO.setRecipientId(manager.getUserId());
            notificationDTO.setActorId(currentUser.getUserId());

            notificationDTO.setType("TASK_SUBMISSION_SUBMITTED");
            notificationDTO.setTitle("任务待审核");
            notificationDTO.setContent("任务“" + task.getTitle() + "”提交了新的成果，等待审核");

            notificationDTO.setProjectId(task.getProjectId());
            notificationDTO.setTargetType("TASK");
            notificationDTO.setTargetId(task.getId());

            notificationService.createNotification(notificationDTO);
        }
        taskSummaryCache.evictAfterCommit(task.getProjectId());

        return submission.getId();
    }

    @Transactional
    public void reviewTask(Long taskId, ReviewTaskDTO dto) {
        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 只有项目负责人可以审核
        projectPermissionService.requireProjectManager(task.getProjectId());

        // 3. 项目必须处于进行中
        Project project = projectMapper.selectByIdForUpdate(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        // 获取项目锁后，重新读取并锁定当前成员关系，确认负责人权限仍然有效。
        projectPermissionService.requireProjectManagerForUpdate(
                task.getProjectId()
        );

        if (!ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "项目未处于进行中，不能审核任务");
        }

        // 4. 任务必须正在等待审核
        if (!TaskStatus.REVIEW.equals(task.getStatus())) {
            throw new BusinessException(409, "当前任务不处于待审核状态");
        }

        // 5. 找到当前待审核的提交记录
        TaskSubmission submission = taskSubmissionMapper.selectPendingSubmission(taskId);

        if (submission == null) {
            throw new BusinessException(409, "没有找到待审核的任务提交记录");
        }

        // 6. 检查审核动作
        String action = dto.getAction().trim().toUpperCase();

        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) {
            throw new BusinessException(400, "审核结果只能是 APPROVE 或 REJECT");
        }

        if ("REJECT".equals(action) && (dto.getReviewNote() == null || dto.getReviewNote().isBlank())) {
            throw new BusinessException(400, "驳回任务时必须填写修改意见");
        }

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        // 7. 先保存修改前的状态
        String oldTaskStatus = task.getStatus() == null ? null : task.getStatus().getCode();

        String oldSubmissionReviewStatus = submission.getReviewStatus() == null ? null : submission.getReviewStatus().getCode();

        // 一定要先保存原负责人
        Long oldAssigneeId = task.getAssigneeId();

        // 8. 保存审核人、审核意见、审核时间
        submission.setReviewerId(currentUser.getUserId());

        submission.setReviewNote(dto.getReviewNote());

        submission.setReviewedAt(now);

        // 判断原任务负责人现在是否仍然有效
        boolean assigneeStillEffective = oldAssigneeId != null && isActiveOrdinaryUser(oldAssigneeId);

        // 9. 根据审核结果修改状态
        if ("APPROVE".equals(action)) {
            // 审核通过
            submission.setReviewStatus(TaskSubmissionStatus.APPROVED);

            task.setStatus(TaskStatus.DONE);
            task.setCompletedAt(now);
        } else {
            // 审核驳回
            submission.setReviewStatus(TaskSubmissionStatus.REJECTED);

            if (assigneeStillEffective) {
                // 原负责人仍然正常
                // 让他继续修改任务
                task.setStatus(TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
            } else {
                // 原负责人已经失效，例如被管理员禁用
                // 任务重新回到 TODO，等待 PM 重新分配
                task.setStatus(TaskStatus.TODO);
                task.setAssigneeId(null);
                task.setCompletedAt(null);
            }
        }

        task.setUpdatedAt(now);

        // 10. 真正修改数据库中的任务
        int taskUpdated;

        if ("REJECT".equals(action) && !assigneeStillEffective) {
            // 特殊情况：
            // 审核驳回 + 原负责人已经失效
            // REVIEW -> TODO，并清空负责人
            taskUpdated = taskMapper.rejectToTodoAndUnassign(taskId, oldAssigneeId, now);
        } else {
            // 正常情况：
            // 批准验收：REVIEW -> DONE
            // 驳回验收且负责人正常：REVIEW -> IN_PROGRESS
            taskUpdated = taskMapper.transitionStatusIfCurrent(
                taskId,
                TaskStatus.REVIEW,
                task.getStatus(),
                task.getCompletedAt(),
                now
            );
        }

        if (taskUpdated != 1) {
            throw new BusinessException(409, "任务已被处理，请刷新后重试");
        }
        int submissionUpdated = taskSubmissionMapper.reviewPendingSubmission(
            submission.getId(),
            taskId,
            submission.getReviewStatus(),
            currentUser.getUserId(),
            dto.getReviewNote(),
            now
        );
        if (submissionUpdated != 1) {
            throw new BusinessException(409, "任务提交记录已被处理，请刷新后重试");
        }

        // 11. 准备修改前的数据
        Map<String, Object> beforeData = new HashMap<>();

        beforeData.put("status", oldTaskStatus);

        beforeData.put("submissionReviewStatus", oldSubmissionReviewStatus);

        // 12. 准备修改后的数据
        Map<String, Object> afterData = new HashMap<>();

        afterData.put("status", task.getStatus() == null ? null : task.getStatus().getCode());

        afterData.put(
            "submissionReviewStatus",
            submission.getReviewStatus() == null ? null : submission.getReviewStatus().getCode()
        );

        afterData.put("submissionId", submission.getId());

        afterData.put("submissionNo", submission.getSubmissionNo());

        // 13. 根据审核结果决定日志动作
        String logAction = "APPROVE".equals(action) ? "APPROVE_TASK" : "REJECT_TASK";

        String description = "APPROVE".equals(action) ? "审核通过任务" : "驳回任务";

        // 14. 写操作日志
        operationLogService.record(
            task.getProjectId(),
            currentUser.getUserId(),
            "TASK",
            task.getId(),
            logAction,
            description,
            beforeData,
            afterData
        );

        Long assigneeId = task.getAssigneeId();

        if (assigneeId != null && isActiveOrdinaryUser(assigneeId) && !assigneeId.equals(currentUser.getUserId())) {
            CreateNotificationDTO notificationDTO = new CreateNotificationDTO();

            notificationDTO.setRecipientId(assigneeId);
            notificationDTO.setActorId(currentUser.getUserId());

            if ("APPROVE".equals(action)) {
                notificationDTO.setType("TASK_SUBMISSION_APPROVED");

                notificationDTO.setTitle("任务审核通过");

                notificationDTO.setContent("你提交的任务“" + task.getTitle() + "”已通过审核");
            } else {
                notificationDTO.setType("TASK_SUBMISSION_REJECTED");

                notificationDTO.setTitle("任务审核未通过");

                notificationDTO.setContent("你提交的任务“" + task.getTitle() + "”未通过审核，请修改后重新提交");
            }

            notificationDTO.setProjectId(task.getProjectId());
            notificationDTO.setTargetType("TASK");
            notificationDTO.setTargetId(task.getId());

            notificationService.createNotification(notificationDTO);
        }
        taskSummaryCache.evictAfterCommit(task.getProjectId());
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

    @Transactional
    public void assignTask(Long taskId, AssignTaskDTO dto) {
        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 只有项目负责人可以分配任务
        projectPermissionService.requireProjectManager(task.getProjectId());

        Project project = projectMapper.selectByIdForUpdate(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!ProjectStatus.PREPARING.equals(project.getStatus()) && !ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "当前项目状态不允许分配任务负责人");
        }

        CurrentUser operator = UserContext.get();
        ProjectMember operatorMember = projectMemberMapper.selectMemberForUpdate(task.getProjectId(), operator.getUserId());
        if (
            !"USER".equals(operator.getSystemRole()) ||
            operatorMember == null ||
            !ProjectMemberStatus.ACTIVE.equals(operatorMember.getStatus()) ||
            !"PROJECT_MANAGER".equals(operatorMember.getRole())
        ) {
            throw new BusinessException(403, "你不是该项目的有效负责人");
        }

        // 3. 只有 TODO 状态允许分配 / 重新分配
        if (!TaskStatus.TODO.equals(task.getStatus())) {
            throw new BusinessException(409, "只有待处理任务可以分配负责人");
        }

        // 4. 等待项目锁后使用当前读，避免读到成员退出前的事务快照。
        // 新负责人必须是该项目 ACTIVE 成员。
        ProjectMember member = projectMemberMapper.selectMemberForUpdate(task.getProjectId(), dto.getAssigneeId());

        if (member == null || !ProjectMemberStatus.ACTIVE.equals(member.getStatus())) {
            throw new BusinessException(409, "任务负责人不是该项目的有效成员");
        }
        User assigneeUser = userMapper.selectByIdForUpdate(dto.getAssigneeId());
        if (
            assigneeUser == null ||
            !UserStatus.ACTIVE.equals(assigneeUser.getStatus()) ||
            !"USER".equals(assigneeUser.getSystemRole())
        ) {
            throw new BusinessException(409, "任务负责人不是该项目的有效成员");
        }

        // 5. 保存修改前的负责人
        Long oldAssigneeId = task.getAssigneeId();

        // 如果本来就是这个负责人，不需要重复修改
        if (Objects.equals(oldAssigneeId, dto.getAssigneeId())) {
            return;
        }

        // 6. 修改负责人
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.reassignTodoIfCurrent(taskId, oldAssigneeId, dto.getAssigneeId(), now);
        if (updated != 1) {
            throw new BusinessException(409, "任务状态或负责人已发生变化，请刷新后重试");
        }
        task.setAssigneeId(dto.getAssigneeId());
        task.setUpdatedAt(now);

        // 7. 准备审计日志的修改前数据
        Map<String, Object> beforeData = new HashMap<>();

        beforeData.put("assigneeId", oldAssigneeId);

        // 8. 准备修改后数据
        Map<String, Object> afterData = new HashMap<>();

        afterData.put("assigneeId", dto.getAssigneeId());

        // 9. 获取当前操作人
        CurrentUser currentUser = UserContext.get();

        // 10. 写操作日志
        operationLogService.record(
            task.getProjectId(),
            currentUser.getUserId(),
            "TASK",
            task.getId(),
            "ASSIGN_TASK",
            "修改任务负责人",
            beforeData,
            afterData
        );

        if (!Objects.equals(currentUser.getUserId(), dto.getAssigneeId())) {
            CreateNotificationDTO notificationDTO = new CreateNotificationDTO();

            notificationDTO.setRecipientId(dto.getAssigneeId());

            notificationDTO.setActorId(currentUser.getUserId());

            notificationDTO.setType("TASK_ASSIGNED");

            notificationDTO.setTitle("任务分配");

            notificationDTO.setContent("你被分配了任务：" + task.getTitle());

            notificationDTO.setProjectId(task.getProjectId());

            notificationDTO.setTargetType("TASK");

            notificationDTO.setTargetId(task.getId());

            notificationService.createNotification(notificationDTO);
        }
    }

    @Transactional
    public void handoverDisabledAssignee(Long taskId, HandoverTaskDTO dto) {
        if (
            dto.reason() == null ||
            dto.reason().isBlank() ||
            dto.reason().trim().length() > 500 ||
            dto.assigneeId() == null ||
            dto.expectedAssigneeId() == null
        ) {
            throw new BusinessException(400, "交接须指定原、新任务负责人及不超过500字的原因");
        }
        Task observed = taskMapper.selectById(taskId);
        if (observed == null || observed.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }
        Long projectId = observed.getProjectId();
        projectPermissionService.requireProjectManager(projectId);
        Project project = projectMapper.selectByIdForUpdate(projectId);
        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }
        if (!Set.of(ProjectStatus.PREPARING, ProjectStatus.IN_PROGRESS).contains(project.getStatus())) {
            throw new BusinessException(409, "当前项目状态不允许交接任务");
        }
        projectPermissionService.requireProjectManagerForUpdate(projectId);
        // 等待项目锁后进行当前读，避免使用提交/开始/交接前的旧任务状态。
        Task task = taskMapper.selectByIdForUpdate(taskId);
        if (
            task == null ||
            !projectId.equals(task.getProjectId()) ||
            !TaskStatus.IN_PROGRESS.equals(task.getStatus()) ||
            !Objects.equals(dto.expectedAssigneeId(), task.getAssigneeId())
        ) {
            throw new BusinessException(409, "任务状态或负责人已发生变化，请刷新后重试");
        }
        if (Objects.equals(dto.expectedAssigneeId(), dto.assigneeId())) {
            throw new BusinessException(409, "新任务负责人不能是原任务负责人");
        }
        ProjectMember target = projectMemberMapper.selectMemberForUpdate(projectId, dto.assigneeId());
        if (
            target == null ||
            !ProjectMemberStatus.ACTIVE.equals(target.getStatus()) ||
            (!"PROJECT_MANAGER".equals(target.getRole()) && !"DEVELOPER".equals(target.getRole()))
        ) {
            throw new BusinessException(409, "新任务负责人不是该项目的有效成员");
        }
        // 固定用户锁顺序；锁住账号资格直到交接事务结束。
        Map<Long, User> accounts = new HashMap<>();
        for (Long userId : java.util.stream.Stream.of(dto.expectedAssigneeId(), dto.assigneeId()).sorted().toList()) {
            accounts.put(userId, userMapper.selectByIdForUpdate(userId));
        }
        User original = accounts.get(dto.expectedAssigneeId());
        User replacement = accounts.get(dto.assigneeId());
        if (original == null || !UserStatus.DISABLED.equals(original.getStatus())) {
            throw new BusinessException(409, "只能交接禁用账号承担的进行中任务");
        }
        if (
            replacement == null ||
            !UserStatus.ACTIVE.equals(replacement.getStatus()) ||
            !"USER".equals(replacement.getSystemRole())
        ) {
            throw new BusinessException(409, "新任务负责人不是该项目的有效成员");
        }
        LocalDateTime now = LocalDateTime.now();
        if (
            taskMapper.handoverDisabledAssigneeIfCurrent(taskId, projectId, dto.expectedAssigneeId(), dto.assigneeId(), now) != 1
        ) {
            throw new BusinessException(409, "任务状态或负责人已发生变化，请刷新后重试");
        }
        Long actorId = UserContext.get().getUserId();
        operationLogService.record(
            projectId,
            actorId,
            "TASK",
            taskId,
            "HANDOVER_TASK",
            "交接禁用成员的任务",
            Map.of("assigneeId", dto.expectedAssigneeId(), "status", TaskStatus.IN_PROGRESS.getCode()),
            Map.of(
                "assigneeId",
                dto.assigneeId(),
                "status",
                TaskStatus.TODO.getCode(),
                "reason",
                dto.reason().trim(),
                "handedOverAt",
                now
            )
        );

        CreateNotificationDTO notification = new CreateNotificationDTO();
        notification.setRecipientId(dto.assigneeId());
        notification.setActorId(actorId);
        notification.setType("TASK_ASSIGNED");
        notification.setTitle("任务已交接给你");
        notification.setContent("任务“" + task.getTitle() + "”已交接给你，请确认开始。");
        notification.setProjectId(projectId);
        notification.setTargetType("TASK");
        notification.setTargetId(taskId);
        notificationService.createNotification(notification);
        taskSummaryCache.evictAfterCommit(projectId);
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

    private boolean isActiveOrdinaryUser(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.isActiveOrdinaryUser();
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

    @Transactional
    public Long addTaskComment(Long taskId, AddTaskCommentDTO dto) {
        // 1. 任务必须存在
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须是该项目有效成员
        projectPermissionService.requireProjectMember(task.getProjectId());

        Project project = projectMapper.selectByIdForUpdate(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!ProjectStatus.PREPARING.equals(project.getStatus()) && !ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "当前项目状态不允许新增评论");
        }

        // 3. 如果这是“回复”，检查被回复评论是否合法
        if (dto.getParentId() != null) {
            TaskComment parentComment = taskCommentMapper.selectById(dto.getParentId());

            if (parentComment == null || parentComment.getDeletedAt() != null) {
                throw new BusinessException(404, "被回复的评论不存在");
            }

            if (!taskId.equals(parentComment.getTaskId())) {
                throw new BusinessException(400, "不能回复其他任务的评论");
            }
        }

        CurrentUser currentUser = UserContext.get();

        // 4. 创建评论
        TaskComment comment = new TaskComment();

        comment.setTaskId(taskId);
        comment.setAuthorId(currentUser.getUserId());
        comment.setParentId(dto.getParentId());
        comment.setContent(dto.getContent().trim());

        taskCommentMapper.insert(comment);

        return comment.getId();
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

    @Transactional
    public void cancelTask(Long taskId, CancelTaskDTO dto) {
        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 只有项目负责人可以取消正式任务
        projectPermissionService.requireProjectManager(task.getProjectId());

        // 3. 检查项目
        Project project = projectMapper.selectByIdForUpdate(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        // 获取项目锁后，重新读取并锁定当前成员关系，确认负责人权限仍然有效。
        projectPermissionService.requireProjectManagerForUpdate(
                task.getProjectId()
        );

        // PREPARING 和 IN_PROGRESS 阶段允许处理任务
        if (!ProjectStatus.PREPARING.equals(project.getStatus()) && !ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "当前项目状态不允许取消任务");
        }

        // 4. 只允许 TODO / IN_PROGRESS 取消
        if (!TaskStatus.TODO.equals(task.getStatus()) && !TaskStatus.IN_PROGRESS.equals(task.getStatus())) {
            throw new BusinessException(409, "只有待处理或进行中的任务可以取消");
        }

        CurrentUser currentUser = UserContext.get();

        LocalDateTime now = LocalDateTime.now();

        // 5. 保存修改前状态
        String oldStatus = task.getStatus() == null ? null : task.getStatus().getCode();

        int updated = taskMapper.cancelIfActive(taskId, dto.getReason().trim(), now);
        if (updated != 1) {
            throw new BusinessException(409, "任务已被处理，请刷新后重试");
        }

        // 6. 同步内存对象，供日志使用
        task.setStatus(TaskStatus.CANCELLED);

        task.setCancelReason(dto.getReason().trim());

        task.setCancelledAt(now);
        task.setCompletedAt(null);
        task.setUpdatedAt(now);

        // 7. 准备审计日志
        Map<String, Object> beforeData = new HashMap<>();

        beforeData.put("status", oldStatus);

        Map<String, Object> afterData = new HashMap<>();

        afterData.put("status", task.getStatus() == null ? null : task.getStatus().getCode());

        afterData.put("cancelReason", task.getCancelReason());

        afterData.put("cancelledAt", task.getCancelledAt());

        // 8. 写日志
        operationLogService.record(
            task.getProjectId(),
            currentUser.getUserId(),
            "TASK",
            task.getId(),
            "CANCEL_TASK",
            "取消任务",
            beforeData,
            afterData
        );
        taskSummaryCache.evictAfterCommit(task.getProjectId());
    }
}
