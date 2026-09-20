package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.vo.TaskCommentVO;
import com.flowdesk.vo.TaskSubmissionVO;
import com.flowdesk.vo.TaskVO;
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

    public TaskService(
            TaskMapper taskMapper,
            ProjectMapper projectMapper,
            ProjectMemberMapper projectMemberMapper,
            ProjectPermissionService projectPermissionService,
            TaskSubmissionMapper taskSubmissionMapper,
            TaskCommentMapper taskCommentMapper,
            OperationLogService operationLogService,
            NotificationService notificationService) {

        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectPermissionService = projectPermissionService;
        this.taskSubmissionMapper = taskSubmissionMapper;
        this.taskCommentMapper = taskCommentMapper;
        this.operationLogService = operationLogService;
        this.notificationService = notificationService;
    }

    @Transactional
    public Long createTask(Long projectId, CreateTaskDTO dto) {

        // 1. 只有项目负责人才能创建正式任务
        projectPermissionService.requireProjectManager(projectId);

        CurrentUser currentUser = UserContext.get();

        // 2. 检查项目是否存在以及当前状态
        Project project = projectMapper.selectById(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {
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

            Set<String> allowedPriorities =
                    Set.of("LOW", "MEDIUM", "HIGH");

            if (!allowedPriorities.contains(priority)) {
                throw new BusinessException(
                        400,
                        "任务优先级只能是 LOW、MEDIUM 或 HIGH"
                );
            }
        }

        // 5. 如果指定负责人，负责人必须是项目 ACTIVE 成员
        if (dto.getAssigneeId() != null) {

            ProjectMember assignee =
                    projectMemberMapper.selectOne(
                            new LambdaQueryWrapper<ProjectMember>()
                                    .eq(ProjectMember::getProjectId, projectId)
                                    .eq(ProjectMember::getUserId, dto.getAssigneeId())
                                    .eq(ProjectMember::getStatus, "ACTIVE")
                    );

            if (assignee == null) {
                throw new BusinessException(
                        409,
                        "任务负责人不是该项目的有效成员"
                );
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

        task.setStatus("TODO");
        task.setPriority(priority);
        task.setDeadline(dto.getDeadline());

        taskMapper.insert(task);

        Map<String, Object> afterData = new HashMap<>();

        afterData.put("title", task.getTitle());
        afterData.put("assigneeId", task.getAssigneeId());
        afterData.put("status", task.getStatus());
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

        if (task.getAssigneeId() != null
                && !task.getAssigneeId().equals(currentUser.getUserId())) {

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(task.getAssigneeId());
            notificationDTO.setActorId(currentUser.getUserId());
            notificationDTO.setType("TASK_ASSIGNED");
            notificationDTO.setTitle("任务分配");
            notificationDTO.setContent(
                    "你被分配了任务：" + task.getTitle()
            );
            notificationDTO.setProjectId(task.getProjectId());
            notificationDTO.setTargetType("TASK");
            notificationDTO.setTargetId(task.getId());

            notificationService.createNotification(notificationDTO);
        }

        return task.getId();
    }

    public List<TaskVO> getProjectTasks(
            Long projectId,
            String scope,
            String status) {

        projectPermissionService.requireProjectMember(projectId);

        String normalizedScope =
                scope.trim().toUpperCase();

        if (!"ALL".equals(normalizedScope)
                && !"MINE".equals(normalizedScope)) {

            throw new BusinessException(
                    400,
                    "任务查看范围只能是 ALL 或 MINE"
            );
        }

        String normalizedStatus =
                status.trim().toUpperCase();

        Set<String> allowedStatuses = Set.of(
                "ALL",
                "TODO",
                "IN_PROGRESS",
                "REVIEW",
                "DONE",
                "CANCELLED"
        );

        if (!allowedStatuses.contains(normalizedStatus)) {
            throw new BusinessException(
                    400,
                    "任务状态筛选条件不正确"
            );
        }

        CurrentUser currentUser = UserContext.get();

        return taskMapper.selectProjectTasks(
                projectId,
                normalizedScope,
                currentUser.getUserId(),
                normalizedStatus
        );
    }

    public List<TaskVO> getMyTasks() {
        return taskMapper.selectMyTasks(UserContext.get().getUserId());
    }

    @Transactional
    public void startTask(Long taskId) {

        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须仍然是项目有效成员
        projectPermissionService.requireProjectMember(
                task.getProjectId()
        );

        CurrentUser currentUser = UserContext.get();

        // 3. 检查项目当前状态
        Project project =
                projectMapper.selectById(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!"IN_PROGRESS".equals(project.getStatus())) {
            throw new BusinessException(
                    409,
                    "项目未处于进行中，不能开始任务"
            );
        }

        // 4. 任务必须已经分配负责人
        if (task.getAssigneeId() == null) {
            throw new BusinessException(
                    409,
                    "任务尚未分配负责人"
            );
        }

        // 5. 只有任务负责人本人才能开始
        if (!currentUser.getUserId().equals(
                task.getAssigneeId())) {

            throw new BusinessException(
                    403,
                    "只有任务负责人可以开始该任务"
            );
        }

        // 6. 必须从 TODO 状态开始
        if (!"TODO".equals(task.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有待处理任务可以开始"
            );
        }

        String oldStatus = task.getStatus();

        // 7. 修改任务状态
        task.setStatus("IN_PROGRESS");
        task.setUpdatedAt(LocalDateTime.now());

        taskMapper.updateById(task);

        Map<String, Object> beforeData = new HashMap<>();
        beforeData.put("status", oldStatus);

        Map<String, Object> afterData = new HashMap<>();
        afterData.put("status", task.getStatus());

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
    }

    @Transactional
    public Long submitTask(Long taskId, SubmitTaskDTO dto) {

        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须仍然属于项目
        projectPermissionService.requireProjectMember(
                task.getProjectId()
        );

        CurrentUser currentUser = UserContext.get();

        // 3. 只有任务负责人本人才能提交
        if (task.getAssigneeId() == null
                || !currentUser.getUserId().equals(task.getAssigneeId())) {

            throw new BusinessException(
                    403,
                    "只有任务负责人可以提交该任务"
            );
        }

        // 4. 项目必须正在进行
        Project project =
                projectMapper.selectById(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!"IN_PROGRESS".equals(project.getStatus())) {
            throw new BusinessException(
                    409,
                    "项目未处于进行中，不能提交任务"
            );
        }

        // 5. 只有进行中的任务才能提交审核
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有进行中的任务可以提交审核"
            );
        }

// 保存任务修改前的状态
        String oldStatus = task.getStatus();

        LocalDateTime now = LocalDateTime.now();

// 6. 计算这是第几次提交
        Integer maxSubmissionNo =
                taskSubmissionMapper.selectMaxSubmissionNo(taskId);

        int submissionNo = maxSubmissionNo + 1;

// 7. 保存提交记录
        TaskSubmission submission = new TaskSubmission();

        submission.setTaskId(taskId);
        submission.setSubmitterId(currentUser.getUserId());
        submission.setSubmissionNo(submissionNo);

        submission.setCompletionNote(
                dto.getCompletionNote().trim()
        );
        submission.setResultUrl(dto.getResultUrl());
        submission.setTestNote(
                dto.getTestNote().trim()
        );

        submission.setReviewStatus("PENDING");
        submission.setSubmittedAt(now);

        taskSubmissionMapper.insert(submission);

// 8. 任务进入待审核状态
        task.setStatus("REVIEW");
        task.setUpdatedAt(now);

        taskMapper.updateById(task);

// 9. 记录提交任务的操作日志
        Map<String, Object> beforeData = new HashMap<>();
        beforeData.put("status", oldStatus);

        Map<String, Object> afterData = new HashMap<>();
        afterData.put("status", task.getStatus());
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

        List<ProjectMember> managers =
                projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(ProjectMember::getProjectId, task.getProjectId())
                                .eq(ProjectMember::getRole, "PROJECT_MANAGER")
                                .eq(ProjectMember::getStatus, "ACTIVE")
                );

        for (ProjectMember manager : managers) {

            // 不给自己发通知
            if (manager.getUserId().equals(currentUser.getUserId())) {
                continue;
            }

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(manager.getUserId());
            notificationDTO.setActorId(currentUser.getUserId());

            notificationDTO.setType("TASK_SUBMISSION_SUBMITTED");
            notificationDTO.setTitle("任务待审核");
            notificationDTO.setContent(
                    "任务“" + task.getTitle() + "”提交了新的成果，等待审核"
            );

            notificationDTO.setProjectId(task.getProjectId());
            notificationDTO.setTargetType("TASK");
            notificationDTO.setTargetId(task.getId());

            notificationService.createNotification(notificationDTO);
        }

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
        projectPermissionService.requireProjectManager(
                task.getProjectId()
        );

        // 3. 项目必须处于进行中
        Project project =
                projectMapper.selectById(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!"IN_PROGRESS".equals(project.getStatus())) {
            throw new BusinessException(
                    409,
                    "项目未处于进行中，不能审核任务"
            );
        }

        // 4. 任务必须正在等待审核
        if (!"REVIEW".equals(task.getStatus())) {
            throw new BusinessException(
                    409,
                    "当前任务不处于待审核状态"
            );
        }

        // 5. 找到当前待审核的提交记录
        TaskSubmission submission =
                taskSubmissionMapper.selectPendingSubmission(taskId);

        if (submission == null) {
            throw new BusinessException(
                    409,
                    "没有找到待审核的任务提交记录"
            );
        }

        // 6. 检查审核动作
        String action =
                dto.getAction().trim().toUpperCase();

        if (!"APPROVE".equals(action)
                && !"REJECT".equals(action)) {

            throw new BusinessException(
                    400,
                    "审核结果只能是 APPROVE 或 REJECT"
            );
        }

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        // 7. 先保存修改前的状态
        String oldTaskStatus = task.getStatus();
        String oldSubmissionReviewStatus =
                submission.getReviewStatus();

        // 8. 保存审核人、审核意见、审核时间
        submission.setReviewerId(currentUser.getUserId());
        submission.setReviewNote(dto.getReviewNote());
        submission.setReviewedAt(now);

        // 9. 根据审核结果修改状态
        if ("APPROVE".equals(action)) {

            submission.setReviewStatus("APPROVED");

            task.setStatus("DONE");
            task.setCompletedAt(now);

        } else {

            submission.setReviewStatus("REJECTED");

            task.setStatus("IN_PROGRESS");
            task.setCompletedAt(null);
        }

        task.setUpdatedAt(now);

        // 10. 更新数据库
        taskSubmissionMapper.updateById(submission);
        taskMapper.updateById(task);

        // 11. 准备修改前的数据
        Map<String, Object> beforeData = new HashMap<>();

        beforeData.put(
                "status",
                oldTaskStatus
        );

        beforeData.put(
                "submissionReviewStatus",
                oldSubmissionReviewStatus
        );

        // 12. 准备修改后的数据
        Map<String, Object> afterData = new HashMap<>();

        afterData.put(
                "status",
                task.getStatus()
        );

        afterData.put(
                "submissionReviewStatus",
                submission.getReviewStatus()
        );

        afterData.put(
                "submissionId",
                submission.getId()
        );

        afterData.put(
                "submissionNo",
                submission.getSubmissionNo()
        );

        // 13. 根据审核结果决定日志动作
        String logAction =
                "APPROVE".equals(action)
                        ? "APPROVE_TASK"
                        : "REJECT_TASK";

        String description =
                "APPROVE".equals(action)
                        ? "审核通过任务"
                        : "驳回任务";

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

        if (assigneeId != null
                && !assigneeId.equals(currentUser.getUserId())) {

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(assigneeId);
            notificationDTO.setActorId(currentUser.getUserId());

            if ("APPROVE".equals(action)) {

                notificationDTO.setType(
                        "TASK_SUBMISSION_APPROVED"
                );

                notificationDTO.setTitle(
                        "任务审核通过"
                );

                notificationDTO.setContent(
                        "你提交的任务“"
                                + task.getTitle()
                                + "”已通过审核"
                );

            } else {

                notificationDTO.setType(
                        "TASK_SUBMISSION_REJECTED"
                );

                notificationDTO.setTitle(
                        "任务审核未通过"
                );

                notificationDTO.setContent(
                        "你提交的任务“"
                                + task.getTitle()
                                + "”未通过审核，请修改后重新提交"
                );
            }

            notificationDTO.setProjectId(task.getProjectId());
            notificationDTO.setTargetType("TASK");
            notificationDTO.setTargetId(task.getId());

            notificationService.createNotification(
                    notificationDTO
            );
        }
    }

    public List<TaskSubmissionVO> getTaskSubmissions(Long taskId) {

        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        projectPermissionService.requireProjectMember(
                task.getProjectId()
        );

        return taskSubmissionMapper.selectTaskSubmissions(taskId);
    }

    @Transactional
    public void assignTask(Long taskId, AssignTaskDTO dto) {

        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(
                    404,
                    "任务不存在"
            );
        }

        // 2. 只有项目负责人可以分配任务
        projectPermissionService.requireProjectManager(
                task.getProjectId()
        );

        Project project =
                projectMapper.selectById(
                        task.getProjectId()
                );

        if (project == null
                || project.getDeletedAt() != null) {

            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许分配任务负责人"
            );
        }

        // 3. 只有 TODO 状态允许分配 / 重新分配
        if (!"TODO".equals(task.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有待处理任务可以分配负责人"
            );
        }

        // 4. 新负责人必须是该项目 ACTIVE 成员
        ProjectMember member =
                projectMemberMapper.selectOne(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(
                                        ProjectMember::getProjectId,
                                        task.getProjectId()
                                )
                                .eq(
                                        ProjectMember::getUserId,
                                        dto.getAssigneeId()
                                )
                                .eq(
                                        ProjectMember::getStatus,
                                        "ACTIVE"
                                )
                );

        if (member == null) {
            throw new BusinessException(
                    409,
                    "任务负责人不是该项目的有效成员"
            );
        }

        // 5. 保存修改前的负责人
        Long oldAssigneeId =
                task.getAssigneeId();

        // 如果本来就是这个负责人，不需要重复修改
        if (Objects.equals(
                oldAssigneeId,
                dto.getAssigneeId())) {
            return;
        }

        // 6. 修改负责人
        task.setAssigneeId(
                dto.getAssigneeId()
        );

        task.setUpdatedAt(
                LocalDateTime.now()
        );

        taskMapper.updateById(task);

        // 7. 准备审计日志的修改前数据
        Map<String, Object> beforeData =
                new HashMap<>();

        beforeData.put(
                "assigneeId",
                oldAssigneeId
        );

        // 8. 准备修改后数据
        Map<String, Object> afterData =
                new HashMap<>();

        afterData.put(
                "assigneeId",
                dto.getAssigneeId()
        );

        // 9. 获取当前操作人
        CurrentUser currentUser =
                UserContext.get();

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

        if (!Objects.equals(
                currentUser.getUserId(),
                dto.getAssigneeId())) {

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(
                    dto.getAssigneeId()
            );

            notificationDTO.setActorId(
                    currentUser.getUserId()
            );

            notificationDTO.setType(
                    "TASK_ASSIGNED"
            );

            notificationDTO.setTitle(
                    "任务分配"
            );

            notificationDTO.setContent(
                    "你被分配了任务：" + task.getTitle()
            );

            notificationDTO.setProjectId(
                    task.getProjectId()
            );

            notificationDTO.setTargetType(
                    "TASK"
            );

            notificationDTO.setTargetId(
                    task.getId()
            );

            notificationService.createNotification(
                    notificationDTO
            );
        }
    }

    public TaskVO getTaskDetail(Long taskId) {

        // 1. 先找到任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须是这个任务所属项目的成员
        projectPermissionService.requireProjectMember(
                task.getProjectId()
        );

        // 3. 查询前端需要的详细信息
        TaskVO taskVO =
                taskMapper.selectTaskDetail(taskId);

        if (taskVO == null) {
            throw new BusinessException(404, "任务不存在");
        }

        return taskVO;
    }

    public Long addTaskComment(
            Long taskId,
            AddTaskCommentDTO dto) {

        // 1. 任务必须存在
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须是该项目有效成员
        projectPermissionService.requireProjectMember(
                task.getProjectId()
        );

        Project project =
                projectMapper.selectById(
                        task.getProjectId()
                );

        if (project == null
                || project.getDeletedAt() != null) {

            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        if ("COMPLETED".equals(project.getStatus())
                || "ARCHIVED".equals(project.getStatus())
                || "CANCELLED".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许新增评论"
            );
        }

        // 3. 如果这是“回复”，检查被回复评论是否合法
        if (dto.getParentId() != null) {

            TaskComment parentComment =
                    taskCommentMapper.selectById(dto.getParentId());

            if (parentComment == null
                    || parentComment.getDeletedAt() != null) {

                throw new BusinessException(
                        404,
                        "被回复的评论不存在"
                );
            }

            if (!taskId.equals(parentComment.getTaskId())) {
                throw new BusinessException(
                        400,
                        "不能回复其他任务的评论"
                );
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

        projectPermissionService.requireProjectMember(
                task.getProjectId()
        );

        return taskCommentMapper.selectTaskComments(taskId);
    }

    @Transactional
    public void cancelTask(
            Long taskId,
            CancelTaskDTO dto) {

        // 1. 查询任务
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(
                    404,
                    "任务不存在"
            );
        }

        // 2. 只有项目负责人可以取消正式任务
        projectPermissionService.requireProjectManager(
                task.getProjectId()
        );

        // 3. 检查项目
        Project project =
                projectMapper.selectById(
                        task.getProjectId()
                );

        if (project == null
                || project.getDeletedAt() != null) {

            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        // PREPARING 和 IN_PROGRESS 阶段允许处理任务
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许取消任务"
            );
        }

        // 4. 只允许 TODO / IN_PROGRESS 取消
        if (!"TODO".equals(task.getStatus())
                && !"IN_PROGRESS".equals(task.getStatus())) {

            throw new BusinessException(
                    409,
                    "只有待处理或进行中的任务可以取消"
            );
        }

        CurrentUser currentUser =
                UserContext.get();

        LocalDateTime now =
                LocalDateTime.now();

        // 5. 保存修改前状态
        String oldStatus =
                task.getStatus();

        // 6. 修改任务
        task.setStatus("CANCELLED");

        task.setCancelReason(
                dto.getReason().trim()
        );

        task.setCancelledAt(now);
        task.setCompletedAt(null);
        task.setUpdatedAt(now);

        taskMapper.updateById(task);

        // 7. 准备审计日志
        Map<String, Object> beforeData =
                new HashMap<>();

        beforeData.put(
                "status",
                oldStatus
        );

        Map<String, Object> afterData =
                new HashMap<>();

        afterData.put(
                "status",
                task.getStatus()
        );

        afterData.put(
                "cancelReason",
                task.getCancelReason()
        );

        afterData.put(
                "cancelledAt",
                task.getCancelledAt()
        );

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
    }
}
