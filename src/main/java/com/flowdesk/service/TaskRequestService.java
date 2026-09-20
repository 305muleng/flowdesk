package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.CreateTaskRequestDTO;
import com.flowdesk.dto.ReviewTaskRequestDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskRequestMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskRequest;
import com.flowdesk.model.User;
import com.flowdesk.vo.TaskRequestVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TaskRequestService {

    private final TaskRequestMapper taskRequestMapper;
    private final ProjectMapper projectMapper;
    private final ProjectPermissionService projectPermissionService;
    private final TaskMapper taskMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final OperationLogService operationLogService;

    public TaskRequestService(
            TaskRequestMapper taskRequestMapper,
            ProjectMapper projectMapper,
            ProjectPermissionService projectPermissionService,
            TaskMapper taskMapper,
            ProjectMemberMapper projectMemberMapper,
            NotificationService notificationService,
            UserMapper userMapper,
            OperationLogService operationLogService) {

        this.taskRequestMapper = taskRequestMapper;
        this.projectMapper = projectMapper;
        this.projectPermissionService = projectPermissionService;
        this.taskMapper = taskMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.operationLogService = operationLogService;
    }

    @Transactional
    public Long createTaskRequest(
            Long projectId,
            CreateTaskRequestDTO dto) {

        // 1. 当前用户必须是项目有效成员
        ProjectMember member =
                projectPermissionService.requireProjectMember(projectId);

        // 2. 只有开发人员需要提交任务申请
        if (!"DEVELOPER".equals(member.getRole())) {
            throw new BusinessException(
                    403,
                    "只有开发人员可以提交任务申请"
            );
        }

        // 3. 项目必须允许产生新任务
        Project project = projectMapper.selectById(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许提交任务申请"
            );
        }

        // 4. 如果填写了建议截止时间，必须在未来
        LocalDateTime now = LocalDateTime.now();

        if (dto.getSuggestedDeadline() != null
                && !dto.getSuggestedDeadline().isAfter(now)) {

            throw new BusinessException(
                    400,
                    "建议截止时间必须晚于当前时间"
            );
        }

        CurrentUser currentUser = UserContext.get();

        // 5. 创建任务申请
        TaskRequest request = new TaskRequest();

        request.setProjectId(projectId);
        request.setRequesterId(currentUser.getUserId());

        request.setTitle(dto.getTitle().trim());
        request.setDescription(dto.getDescription());
        request.setGoal(dto.getGoal());
        request.setSuggestedDeadline(dto.getSuggestedDeadline());

        request.setStatus("PENDING");

        taskRequestMapper.insert(request);

        operationLogService.record(
                projectId, currentUser.getUserId(), "TASK_REQUEST", request.getId(),
                "CREATE_TASK_REQUEST", "提交任务申请", null,
                Map.of("status", request.getStatus(), "title", request.getTitle()));

        List<ProjectMember> managers =
                projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(ProjectMember::getProjectId, projectId)
                                .eq(ProjectMember::getRole, "PROJECT_MANAGER")
                                .eq(ProjectMember::getStatus, "ACTIVE")
                );

        for (ProjectMember manager : managers) {

            if (!isActiveProjectUser(manager.getUserId())) {
                continue;
            }

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(
                    manager.getUserId()
            );

            notificationDTO.setActorId(
                    currentUser.getUserId()
            );

            notificationDTO.setType(
                    "TASK_REQUEST_SUBMITTED"
            );

            notificationDTO.setTitle(
                    "新的任务申请"
            );

            notificationDTO.setContent(
                    "有新的任务申请：" + request.getTitle()
            );

            notificationDTO.setProjectId(projectId);

            notificationDTO.setTargetType(
                    "TASK_REQUEST"
            );

            notificationDTO.setTargetId(
                    request.getId()
            );

            notificationService.createNotification(
                    notificationDTO
            );
        }

        return request.getId();
    }

    public List<TaskRequestVO> getProjectTaskRequests(
            Long projectId,
            String status) {

        // 只有项目负责人查看整个项目的任务申请
        projectPermissionService.requireProjectManager(projectId);

        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        String normalizedStatus =
                status.trim().toUpperCase();

        Set<String> allowedStatuses = Set.of(
                "ALL",
                "PENDING",
                "APPROVED",
                "REJECTED",
                "CANCELLED"
        );

        if (!allowedStatuses.contains(normalizedStatus)) {
            throw new BusinessException(
                    400,
                    "任务申请状态筛选条件不正确"
            );
        }

        return taskRequestMapper.selectProjectTaskRequests(
                projectId,
                normalizedStatus
        );
    }

    public List<TaskRequestVO> getMyTaskRequests(String status) {
        String normalizedStatus = normalizeStatus(status);
        if (!"USER".equals(UserContext.get().getSystemRole())) {
            return List.of();
        }
        return taskRequestMapper.selectMyTaskRequests(
                UserContext.get().getUserId(),
                normalizedStatus
        );
    }

    private String normalizeStatus(String status) {
        String normalizedStatus = status.trim().toUpperCase();
        if (!Set.of("ALL", "PENDING", "APPROVED", "REJECTED", "CANCELLED")
                .contains(normalizedStatus)) {
            throw new BusinessException(400, "任务申请状态筛选条件不正确");
        }
        return normalizedStatus;
    }

    @Transactional
    public Long reviewTaskRequest(
            Long projectId,
            Long requestId,
            ReviewTaskRequestDTO dto) {

        // 1. 当前用户必须是项目负责人
        projectPermissionService.requireProjectManager(projectId);

        // 2. 查询项目
        Project project =
                projectMapper.selectById(projectId);

        if (project == null
                || project.getDeletedAt() != null) {

            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        // 3. 只有准备中 / 进行中的项目才能审批任务申请
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许审批任务申请"
            );
        }

        CurrentUser currentUser = UserContext.get();

        // 4. 查询申请
        TaskRequest request =
                taskRequestMapper.selectById(requestId);

        if (request == null
                || !projectId.equals(request.getProjectId())) {

            throw new BusinessException(
                    404,
                    "任务申请不存在"
            );
        }

        // 3. 只能处理 PENDING
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessException(
                    409,
                    "该任务申请已经处理"
            );
        }

        String action =
                dto.getAction().trim().toUpperCase();

        if (!"APPROVE".equals(action)
                && !"REJECT".equals(action)) {

            throw new BusinessException(
                    400,
                    "审批结果只能是 APPROVE 或 REJECT"
            );
        }

        LocalDateTime now = LocalDateTime.now();

        // 4. 拒绝
        if ("REJECT".equals(action)) {

            int updated = taskRequestMapper.reviewIfPending(
                    requestId, projectId, "REJECTED", currentUser.getUserId(),
                    dto.getReviewNote(), now);
            if (updated != 1) {
                throw new BusinessException(409, "该任务申请已经处理，请刷新后重试");
            }

            request.setStatus("REJECTED");
            request.setReviewerId(currentUser.getUserId());
            request.setReviewNote(dto.getReviewNote());
            request.setReviewedAt(now);

            operationLogService.record(
                    projectId, currentUser.getUserId(), "TASK_REQUEST", request.getId(),
                    "REJECT_TASK_REQUEST", "驳回任务申请",
                    Map.of("status", "PENDING"), Map.of("status", "REJECTED"));

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(request.getRequesterId());
            notificationDTO.setActorId(currentUser.getUserId());
            notificationDTO.setType("TASK_REQUEST_REJECTED");
            notificationDTO.setTitle("任务申请未通过");
            notificationDTO.setContent(
                    "你的任务申请“" + request.getTitle() + "”未通过"
            );
            notificationDTO.setProjectId(projectId);
            notificationDTO.setTargetType("TASK_REQUEST");
            notificationDTO.setTargetId(request.getId());

            notificationService.createNotification(notificationDTO);

            return null;
        }

        // 下面全部属于 APPROVE

        ProjectMember requesterMembership = projectMemberMapper.selectOne(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, request.getRequesterId())
                        .eq(ProjectMember::getStatus, "ACTIVE"));
        if (requesterMembership == null || !isActiveProjectUser(request.getRequesterId())) {
            throw new BusinessException(409, "任务申请人已不是该项目的有效成员");
        }

        // 5. 正式任务 deadline 必填
        if (dto.getDeadline() == null) {
            throw new BusinessException(
                    400,
                    "批准任务申请时必须设置正式截止时间"
            );
        }

        if (!dto.getDeadline().isAfter(now)) {
            throw new BusinessException(
                    400,
                    "正式任务截止时间必须晚于当前时间"
            );
        }

        // 6. priority
        String priority = dto.getPriority();

        if (priority == null || priority.isBlank()) {
            priority = "MEDIUM";
        } else {

            priority = priority.trim().toUpperCase();

            if (!Set.of("LOW", "MEDIUM", "HIGH")
                    .contains(priority)) {

                throw new BusinessException(
                        400,
                        "任务优先级只能是 LOW、MEDIUM 或 HIGH"
                );
            }
        }

        // 7. 如果指定负责人，必须是项目 ACTIVE 成员
        if (dto.getAssigneeId() != null) {

            ProjectMember assignee =
                    projectMemberMapper.selectOne(
                            new LambdaQueryWrapper<ProjectMember>()
                                    .eq(ProjectMember::getProjectId, projectId)
                                    .eq(ProjectMember::getUserId,
                                            dto.getAssigneeId())
                                    .eq(ProjectMember::getStatus, "ACTIVE")
                    );

            if (assignee == null || !isActiveProjectUser(dto.getAssigneeId())) {
                throw new BusinessException(
                        409,
                        "任务负责人不是该项目的有效成员"
                );
            }
        }

        int reviewed = taskRequestMapper.reviewIfPending(
                requestId, projectId, "APPROVED", currentUser.getUserId(),
                dto.getReviewNote(), now);
        if (reviewed != 1) {
            throw new BusinessException(409, "该任务申请已经处理，请刷新后重试");
        }

        // 8. 创建正式 Task
        Task task = new Task();

        task.setProjectId(projectId);

        // 注意：creator 是审批人 Tom
        task.setCreatorId(currentUser.getUserId());

        task.setAssigneeId(dto.getAssigneeId());

        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setGoal(request.getGoal());

        task.setStatus("TODO");
        task.setPriority(priority);
        task.setDeadline(dto.getDeadline());

        taskMapper.insert(task);

        // 9. 更新申请
        request.setStatus("APPROVED");
        request.setReviewerId(currentUser.getUserId());
        request.setReviewNote(dto.getReviewNote());

        // 把申请和正式任务关联起来
        request.setTaskId(task.getId());

        request.setReviewedAt(now);

        taskRequestMapper.updateById(request);

        Map<String, Object> approvedData = new HashMap<>();
        approvedData.put("status", "APPROVED");
        approvedData.put("taskId", task.getId());
        operationLogService.record(
                projectId, currentUser.getUserId(), "TASK_REQUEST", request.getId(),
                "APPROVE_TASK_REQUEST", "批准任务申请并创建正式任务",
                Map.of("status", "PENDING"), approvedData);

        CreateNotificationDTO approvedNotification =
                new CreateNotificationDTO();

        approvedNotification.setRecipientId(request.getRequesterId());
        approvedNotification.setActorId(currentUser.getUserId());
        approvedNotification.setType("TASK_REQUEST_APPROVED");
        approvedNotification.setTitle("任务申请已通过");
        if (request.getRequesterId().equals(dto.getAssigneeId())) {

            approvedNotification.setContent(
                    "你的任务申请“"
                            + request.getTitle()
                            + "”已通过，并已分配给你"
            );

        } else {

            approvedNotification.setContent(
                    "你的任务申请“"
                            + request.getTitle()
                            + "”已通过"
            );
        }
        approvedNotification.setProjectId(projectId);

// 已经生成正式 Task，所以点击通知直接看 Task 更实用
        approvedNotification.setTargetType("TASK");
        approvedNotification.setTargetId(task.getId());

        notificationService.createNotification(approvedNotification);

        Long assigneeId = dto.getAssigneeId();

        if (assigneeId != null
                && !assigneeId.equals(currentUser.getUserId())
                && !assigneeId.equals(request.getRequesterId())) {

            CreateNotificationDTO assignedNotification =
                    new CreateNotificationDTO();

            assignedNotification.setRecipientId(assigneeId);
            assignedNotification.setActorId(currentUser.getUserId());
            assignedNotification.setType("TASK_ASSIGNED");
            assignedNotification.setTitle("任务分配");
            assignedNotification.setContent(
                    "你被分配了任务：" + task.getTitle()
            );
            assignedNotification.setProjectId(projectId);
            assignedNotification.setTargetType("TASK");
            assignedNotification.setTargetId(task.getId());

            notificationService.createNotification(assignedNotification);
        }

        return task.getId();
    }

    private boolean isActiveProjectUser(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null
                && "ACTIVE".equals(user.getStatus())
                && "USER".equals(user.getSystemRole());
    }

    @Transactional
    public void cancelTaskRequest(
            Long projectId,
            Long requestId) {

        CurrentUser currentUser = UserContext.get();

        projectPermissionService.requireProjectMember(projectId);

        // 1. 查询申请
        TaskRequest request =
                taskRequestMapper.selectById(requestId);

        if (request == null
                || !projectId.equals(request.getProjectId())) {

            throw new BusinessException(
                    404,
                    "任务申请不存在"
            );
        }

        // 2. 只能撤回自己的申请
        if (!currentUser.getUserId().equals(
                request.getRequesterId())) {

            throw new BusinessException(
                    403,
                    "只能撤回自己提交的任务申请"
            );
        }

        // 3. 只有 PENDING 可以撤回
        if (!"PENDING".equals(request.getStatus())) {

            throw new BusinessException(
                    409,
                    "只有待审批的任务申请可以撤回"
            );
        }

        // 4. 以 PENDING 为前置条件撤回，避免与审批并发双重生效
        LocalDateTime cancelledAt = LocalDateTime.now();
        int updated = taskRequestMapper.cancelIfPending(
                requestId, projectId, currentUser.getUserId(), cancelledAt);
        if (updated != 1) {
            throw new BusinessException(409, "该任务申请已经处理，请刷新后重试");
        }
        request.setStatus("CANCELLED");
        request.setCancelledAt(cancelledAt);

        operationLogService.record(
                projectId, currentUser.getUserId(), "TASK_REQUEST", request.getId(),
                "CANCEL_TASK_REQUEST", "撤回任务申请",
                Map.of("status", "PENDING"), Map.of("status", "CANCELLED"));
    }
}
