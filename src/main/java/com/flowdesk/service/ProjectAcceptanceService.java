package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.ReviewProjectAcceptanceDTO;
import com.flowdesk.dto.SubmitProjectAcceptanceDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.vo.ProjectAcceptanceVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectAcceptanceService {

    private final ProjectAcceptanceMapper projectAcceptanceMapper;
    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final ProjectPermissionService projectPermissionService;
    private final OperationLogService operationLogService;
    private final TaskRequestMapper taskRequestMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;

    public ProjectAcceptanceService(
            ProjectAcceptanceMapper projectAcceptanceMapper,
            ProjectMapper projectMapper,
            TaskMapper taskMapper,
            ProjectPermissionService projectPermissionService,
            OperationLogService operationLogService,
            TaskRequestMapper taskRequestMapper,
            UserMapper userMapper,
            NotificationService notificationService) {

        this.projectAcceptanceMapper = projectAcceptanceMapper;
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.projectPermissionService = projectPermissionService;
        this.operationLogService = operationLogService;
        this.taskRequestMapper = taskRequestMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
    }

    @Transactional
    public Long submitAcceptance(
            Long projectId,
            SubmitProjectAcceptanceDTO dto) {

        // 1. 只有项目负责人可以提交项目验收
        projectPermissionService.requireProjectManager(projectId);

        Project project = projectMapper.selectById(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        // 2. 项目必须正在进行
        if (!"IN_PROGRESS".equals(project.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有进行中的项目可以提交验收"
            );
        }

        // 3. 不能还有未完成任务
        Long unfinishedTaskCount = taskMapper.selectCount(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getProjectId, projectId)
                        .isNull(Task::getDeletedAt)
                        .in(
                                Task::getStatus,
                                List.of(
                                        "TODO",
                                        "IN_PROGRESS",
                                        "REVIEW"
                                )
                        )
        );

        if (unfinishedTaskCount > 0) {
            throw new BusinessException(
                    409,
                    "项目仍有未完成任务，不能提交验收"
            );
        }

        // 4. 不能还有待处理的任务申请
        Long pendingTaskRequestCount =
                taskRequestMapper.selectCount(
                        new LambdaQueryWrapper<TaskRequest>()
                                .eq(
                                        TaskRequest::getProjectId,
                                        projectId
                                )
                                .eq(
                                        TaskRequest::getStatus,
                                        "PENDING"
                                )
                );

        if (pendingTaskRequestCount > 0) {
            throw new BusinessException(
                    409,
                    "项目仍有待处理的任务申请，不能提交验收"
            );
        }

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

// 保存项目修改前状态
        String oldProjectStatus = project.getStatus();

// 4. 计算这是第几次验收
        Integer maxAcceptanceNo =
                projectAcceptanceMapper
                        .selectMaxAcceptanceNo(projectId);

        int acceptanceNo =
                maxAcceptanceNo == null
                        ? 1
                        : maxAcceptanceNo + 1;

// 5. 创建验收记录
        ProjectAcceptance acceptance =
                new ProjectAcceptance();

        acceptance.setProjectId(projectId);
        acceptance.setSubmitterId(
                currentUser.getUserId()
        );
        acceptance.setAcceptanceNo(acceptanceNo);

        acceptance.setSubmissionNote(
                dto.getSubmissionNote().trim()
        );

        acceptance.setReviewStatus("PENDING");
        acceptance.setSubmittedAt(now);

        projectAcceptanceMapper.insert(acceptance);

// 6. 项目进入待验收状态
        project.setStatus("PENDING_ACCEPTANCE");
        project.setUpdatedAt(now);

        projectMapper.updateById(project);

// 7. 记录项目验收提交日志
        Map<String, Object> beforeData =
                new HashMap<>();

        beforeData.put(
                "status",
                oldProjectStatus
        );

        Map<String, Object> afterData =
                new HashMap<>();

        afterData.put(
                "status",
                project.getStatus()
        );

        afterData.put(
                "acceptanceId",
                acceptance.getId()
        );

        afterData.put(
                "acceptanceNo",
                acceptance.getAcceptanceNo()
        );

        afterData.put(
                "acceptanceReviewStatus",
                acceptance.getReviewStatus()
        );

        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "PROJECT",
                projectId,
                "SUBMIT_PROJECT_ACCEPTANCE",
                "提交项目验收",
                beforeData,
                afterData
        );

        List<User> admins =
                userMapper.selectList(
                        new LambdaQueryWrapper<User>()
                                .eq(User::getSystemRole, "SYSTEM_ADMIN")
                                .eq(User::getStatus, "ACTIVE")
                );

        for (User admin : admins) {

            // 不给自己发通知
            if (admin.getId().equals(currentUser.getUserId())) {
                continue;
            }

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(admin.getId());
            notificationDTO.setActorId(currentUser.getUserId());

            notificationDTO.setType(
                    "PROJECT_ACCEPTANCE_SUBMITTED"
            );

            notificationDTO.setTitle(
                    "新的项目验收申请"
            );

            notificationDTO.setContent(
                    "项目“" + project.getName() + "”提交了验收申请"
            );

            notificationDTO.setProjectId(projectId);
            notificationDTO.setTargetType("PROJECT_ACCEPTANCE");
            notificationDTO.setTargetId(acceptance.getId());

            notificationService.createNotification(
                    notificationDTO
            );
        }

        return acceptance.getId();
    }

    public List<ProjectAcceptanceVO> getAcceptances(String status) {

        String normalizedStatus =
                status.trim().toUpperCase();

        if (!Set.of(
                "ALL",
                "PENDING",
                "APPROVED",
                "REJECTED"
        ).contains(normalizedStatus)) {

            throw new BusinessException(
                    400,
                    "验收状态筛选条件不正确"
            );
        }

        return projectAcceptanceMapper.selectAcceptances(
                normalizedStatus
        );
    }

    public List<ProjectAcceptanceVO> getProjectAcceptances(Long projectId) {
        projectPermissionService.requireProjectMember(projectId);
        return projectAcceptanceMapper.selectProjectAcceptances(projectId);
    }

    @Transactional
    public void reviewAcceptance(
            Long acceptanceId,
            ReviewProjectAcceptanceDTO dto) {

        ProjectAcceptance acceptance =
                projectAcceptanceMapper.selectById(acceptanceId);

        if (acceptance == null) {
            throw new BusinessException(
                    404,
                    "项目验收记录不存在"
            );
        }

        // 这一轮验收必须还没处理
        if (!"PENDING".equals(acceptance.getReviewStatus())) {
            throw new BusinessException(
                    409,
                    "该项目验收已经处理"
            );
        }

        Project project =
                projectMapper.selectById(
                        acceptance.getProjectId()
                );

        if (project == null
                || project.getDeletedAt() != null) {

            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        // 整个项目必须处于待验收状态
        if (!"PENDING_ACCEPTANCE".equals(project.getStatus())) {
            throw new BusinessException(
                    409,
                    "项目当前不处于待验收状态"
            );
        }

        CurrentUser currentUser = UserContext.get();

        // 不允许提交人自己审核自己
        if (currentUser.getUserId().equals(
                acceptance.getSubmitterId())) {

            throw new BusinessException(
                    403,
                    "不能审核自己提交的项目验收"
            );
        }

        String action =
                dto.getAction().trim().toUpperCase();

        if (!"APPROVE".equals(action)
                && !"REJECT".equals(action)) {

            throw new BusinessException(
                    400,
                    "验收结果只能是 APPROVE 或 REJECT"
            );
        }

        LocalDateTime now = LocalDateTime.now();

        // 先保存修改前的数据
        String oldProjectStatus =
                project.getStatus();

        String oldAcceptanceReviewStatus =
                acceptance.getReviewStatus();

        // 保存审核信息
        acceptance.setReviewerId(
                currentUser.getUserId()
        );

        acceptance.setReviewNote(
                dto.getReviewNote()
        );

        acceptance.setReviewedAt(now);

        // 根据审核结果修改状态
        if ("APPROVE".equals(action)) {

            acceptance.setReviewStatus("APPROVED");

            project.setStatus("COMPLETED");
            project.setActualEndTime(now);

        } else {

            acceptance.setReviewStatus("REJECTED");

            project.setStatus("IN_PROGRESS");
            project.setActualEndTime(null);
        }

        project.setUpdatedAt(now);

        // 更新数据库
        projectAcceptanceMapper.updateById(acceptance);
        projectMapper.updateById(project);

        // 准备修改前的数据
        Map<String, Object> beforeData =
                new HashMap<>();

        beforeData.put(
                "status",
                oldProjectStatus
        );

        beforeData.put(
                "acceptanceReviewStatus",
                oldAcceptanceReviewStatus
        );

        // 准备修改后的数据
        Map<String, Object> afterData =
                new HashMap<>();

        afterData.put(
                "status",
                project.getStatus()
        );

        afterData.put(
                "acceptanceReviewStatus",
                acceptance.getReviewStatus()
        );

        afterData.put(
                "acceptanceId",
                acceptance.getId()
        );

        afterData.put(
                "acceptanceNo",
                acceptance.getAcceptanceNo()
        );

        // 根据通过 / 驳回决定日志动作
        String logAction =
                "APPROVE".equals(action)
                        ? "APPROVE_PROJECT_ACCEPTANCE"
                        : "REJECT_PROJECT_ACCEPTANCE";

        String description =
                "APPROVE".equals(action)
                        ? "项目验收通过"
                        : "项目验收驳回";

        // 写审计日志
        operationLogService.record(
                project.getId(),
                currentUser.getUserId(),
                "PROJECT",
                project.getId(),
                logAction,
                description,
                beforeData,
                afterData
        );

        Long submitterId = acceptance.getSubmitterId();

        if (!submitterId.equals(currentUser.getUserId())) {

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(submitterId);
            notificationDTO.setActorId(currentUser.getUserId());

            if ("APPROVE".equals(action)) {

                notificationDTO.setType(
                        "PROJECT_ACCEPTANCE_APPROVED"
                );

                notificationDTO.setTitle(
                        "项目验收已通过"
                );

                notificationDTO.setContent(
                        "项目“"
                                + project.getName()
                                + "”已通过验收"
                );

            } else {

                notificationDTO.setType(
                        "PROJECT_ACCEPTANCE_REJECTED"
                );

                notificationDTO.setTitle(
                        "项目验收未通过"
                );

                notificationDTO.setContent(
                        "项目“"
                                + project.getName()
                                + "”验收未通过，请根据审核意见进行调整"
                );
            }

            notificationDTO.setProjectId(project.getId());
            notificationDTO.setTargetType("PROJECT_ACCEPTANCE");
            notificationDTO.setTargetId(acceptance.getId());

            notificationService.createNotification(
                    notificationDTO
            );
        }
    }
}