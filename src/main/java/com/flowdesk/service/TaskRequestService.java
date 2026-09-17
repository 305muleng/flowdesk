package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateTaskRequestDTO;
import com.flowdesk.dto.ReviewTaskRequestDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskRequestMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskRequest;
import com.flowdesk.vo.TaskRequestVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class TaskRequestService {

    private final TaskRequestMapper taskRequestMapper;
    private final ProjectMapper projectMapper;
    private final ProjectPermissionService projectPermissionService;
    private final TaskMapper taskMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public TaskRequestService(
            TaskRequestMapper taskRequestMapper,
            ProjectMapper projectMapper,
            ProjectPermissionService projectPermissionService,
            TaskMapper taskMapper,
            ProjectMemberMapper projectMemberMapper) {

        this.taskRequestMapper = taskRequestMapper;
        this.projectMapper = projectMapper;
        this.projectPermissionService = projectPermissionService;
        this.taskMapper = taskMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

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

        return request.getId();
    }

    public List<TaskRequestVO> getProjectTaskRequests(
            Long projectId,
            String status) {

        // 只有项目负责人查看整个项目的任务申请
        projectPermissionService.requireProjectManager(projectId);

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

            request.setStatus("REJECTED");
            request.setReviewerId(currentUser.getUserId());
            request.setReviewNote(dto.getReviewNote());
            request.setReviewedAt(now);

            taskRequestMapper.updateById(request);

            return null;
        }

        // 下面全部属于 APPROVE

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

            if (assignee == null) {
                throw new BusinessException(
                        409,
                        "任务负责人不是该项目的有效成员"
                );
            }
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

        return task.getId();
    }

    public void cancelTaskRequest(
            Long projectId,
            Long requestId) {

        CurrentUser currentUser = UserContext.get();

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

        // 4. 修改状态
        request.setStatus("CANCELLED");
        request.setCancelledAt(LocalDateTime.now());

        taskRequestMapper.updateById(request);
    }
}