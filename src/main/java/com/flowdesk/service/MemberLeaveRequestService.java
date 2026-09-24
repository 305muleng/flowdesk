package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateMemberLeaveRequestDTO;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.ReviewMemberLeaveRequestDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.MemberLeaveRequest;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.vo.MemberLeaveRequestVO;
import com.flowdesk.vo.ProjectMemberVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class MemberLeaveRequestService {

    private final MemberLeaveRequestMapper memberLeaveRequestMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectPermissionService projectPermissionService;
    private final TaskMapper taskMapper;
    private final NotificationService notificationService;
    private final OperationLogService operationLogService;
    private final int expireDays;
    private final TaskRequestMapper taskRequestMapper;

    public MemberLeaveRequestService(
            MemberLeaveRequestMapper memberLeaveRequestMapper,
            ProjectMapper projectMapper,
            ProjectMemberMapper projectMemberMapper,
            ProjectPermissionService projectPermissionService,
            TaskMapper taskMapper,
            NotificationService notificationService,
            OperationLogService operationLogService,
            TaskRequestMapper taskRequestMapper,
            @Value("${member-leave.expire-days}") int expireDays) {

        this.memberLeaveRequestMapper = memberLeaveRequestMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectPermissionService = projectPermissionService;
        this.taskMapper = taskMapper;
        this.notificationService = notificationService;
        this.operationLogService = operationLogService;
        this.expireDays = expireDays;
        this.taskRequestMapper = taskRequestMapper;
    }

    @Transactional
    public Long createLeaveRequest(
            Long projectId,
            CreateMemberLeaveRequestDTO dto) {

        // 1. 当前用户必须是项目有效成员
        ProjectMember member =
                projectPermissionService.requireProjectMember(projectId);

        // 2. 只有开发人员可以申请退出
        if (!"DEVELOPER".equals(member.getRole())) {
            throw new BusinessException(
                    403,
                    "只有开发人员可以申请退出项目"
            );
        }

        // 3. 锁住项目，避免成员变更并发冲突
        Project project =
                projectMapper.selectByIdForUpdate(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        // 4. 只有 PREPARING / IN_PROGRESS 可以申请退出
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许申请退出"
            );
        }

        CurrentUser currentUser = UserContext.get();
        Long userId = currentUser.getUserId();
        LocalDateTime now = LocalDateTime.now();

        // 5. 有未完成任务时不能申请退出
        Long unfinishedTaskCount =
                taskMapper.selectCount(
                        new LambdaQueryWrapper<Task>()
                                .eq(Task::getProjectId, projectId)
                                .eq(Task::getAssigneeId, userId)
                                .in(
                                        Task::getStatus,
                                        Set.of(
                                                "TODO",
                                                "IN_PROGRESS",
                                                "REVIEW"
                                        )
                                )
                                .isNull(Task::getDeletedAt)
                );

        if (unfinishedTaskCount > 0) {
            throw new BusinessException(
                    409,
                    "当前仍有未完成任务，请先完成或重新分配任务"
            );
        }

        // 6. 不能重复提交仍然有效的 PENDING 退出申请
        Long pendingCount =
                memberLeaveRequestMapper.selectCount(
                        new LambdaQueryWrapper<MemberLeaveRequest>()
                                .eq(
                                        MemberLeaveRequest::getProjectId,
                                        projectId
                                )
                                .eq(
                                        MemberLeaveRequest::getApplicantId,
                                        userId
                                )
                                .eq(
                                        MemberLeaveRequest::getStatus,
                                        "PENDING"
                                )
                                .gt(
                                        MemberLeaveRequest::getExpiresAt,
                                        now
                                )
                );

        if (pendingCount > 0) {
            throw new BusinessException(
                    409,
                    "你已有待处理的退出申请"
            );
        }

        // 7. 创建退出申请
        MemberLeaveRequest request =
                new MemberLeaveRequest();

        request.setProjectId(projectId);
        request.setApplicantId(userId);
        request.setReason(dto.getReason().trim());
        request.setStatus("PENDING");
        request.setExpiresAt(
                now.plusDays(expireDays)
        );

        memberLeaveRequestMapper.insert(request);

        // 8. 写项目操作日志
        operationLogService.record(
                projectId,
                userId,
                "MEMBER_LEAVE_REQUEST",
                request.getId(),
                "CREATE_MEMBER_LEAVE_REQUEST",
                "提交退出项目申请",
                null,
                Map.of(
                        "status", "PENDING",
                        "applicantId", userId
                )
        );

        // 9. 给当前有效项目负责人发送通知
        ProjectMemberVO manager =
                projectMemberMapper.selectActiveProjectManager(projectId);

        if (manager != null) {

            CreateNotificationDTO notification =
                    new CreateNotificationDTO();

            notification.setRecipientId(
                    manager.getUserId()
            );

            notification.setActorId(userId);

            notification.setType(
                    "MEMBER_LEAVE_REQUEST_SUBMITTED"
            );

            notification.setTitle(
                    "成员退出申请"
            );

            notification.setContent(
                    "有项目成员提交了退出申请"
            );

            notification.setProjectId(projectId);

            notification.setTargetType(
                    "MEMBER_LEAVE_REQUEST"
            );

            notification.setTargetId(
                    request.getId()
            );

            notificationService.createNotification(
                    notification
            );
        }

        return request.getId();
    }

    @Transactional
    public void cancelLeaveRequest(
            Long projectId,
            Long requestId) {

        CurrentUser currentUser = UserContext.get();

        // 1. 当前用户必须还是项目有效成员
        projectPermissionService.requireProjectMember(projectId);

        // 2. 查询退出申请
        MemberLeaveRequest request =
                memberLeaveRequestMapper.selectById(requestId);

        if (request == null
                || !projectId.equals(request.getProjectId())) {

            throw new BusinessException(
                    404,
                    "退出申请不存在"
            );
        }

        // 3. 只能撤回自己的退出申请
        if (!currentUser.getUserId().equals(
                request.getApplicantId())) {

            throw new BusinessException(
                    403,
                    "只能撤回自己提交的退出申请"
            );
        }

        // 4. 只有 PENDING 可以撤回
        if (!"PENDING".equals(request.getStatus())) {

            throw new BusinessException(
                    409,
                    "只有待审批的退出申请可以撤回"
            );
        }

        LocalDateTime now = LocalDateTime.now();

        // 5. 已经过期的申请不能再撤回
        if (!request.getExpiresAt().isAfter(now)) {

            throw new BusinessException(
                    409,
                    "退出申请已经过期"
            );
        }

        // 6. 真正修改数据库
        int updated =
                memberLeaveRequestMapper.cancelIfPending(
                        requestId,
                        projectId,
                        currentUser.getUserId(),
                        now,
                        now
                );

        if (updated != 1) {
            throw new BusinessException(
                    409,
                    "该退出申请已经处理或已经过期，请刷新后重试"
            );
        }

        // 7. 更新当前 Java 对象
        request.setStatus("CANCELLED");
        request.setCancelledAt(now);

        // 8. 写操作日志
        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "MEMBER_LEAVE_REQUEST",
                request.getId(),
                "CANCEL_MEMBER_LEAVE_REQUEST",
                "撤回退出项目申请",
                Map.of(
                        "status",
                        "PENDING"
                ),
                Map.of(
                        "status",
                        "CANCELLED"
                )
        );
    }

    public MemberLeaveRequestVO getLeaveRequestDetail(
            Long projectId,
            Long requestId) {

        CurrentUser currentUser = UserContext.get();

        // 先查业务记录
        MemberLeaveRequestVO vo =
                memberLeaveRequestMapper.selectDetail(
                        projectId,
                        requestId
                );

        if (vo == null) {
            throw new BusinessException(
                    404,
                    "退出申请不存在"
            );
        }

        // 申请人本人，即使已经退出项目，也可以查看自己的历史申请
        boolean isApplicant =
                currentUser.getUserId()
                        .equals(vo.getApplicantId());

        if (!isApplicant) {

            // 如果不是申请人本人，
            // 那就必须是当前项目负责人
            projectPermissionService
                    .requireProjectManager(projectId);
        }

        // 懒过期
        if ("PENDING".equals(vo.getStatus())
                && !vo.getExpiresAt()
                .isAfter(LocalDateTime.now())) {

            vo.setStatus("EXPIRED");
        }

        return vo;
    }

    @Transactional
    public void reviewLeaveRequest(
            Long projectId,
            Long requestId,
            ReviewMemberLeaveRequestDTO dto) {

        // 1. 只有项目负责人能审批
        projectPermissionService.requireProjectManager(projectId);

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        // 2. 锁住项目
        Project project =
                projectMapper.selectByIdForUpdate(projectId);

        if (project == null
                || project.getDeletedAt() != null) {

            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        // 3. 当前项目必须允许成员变化
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许审批退出申请"
            );
        }

        // 4. 查询申请
        MemberLeaveRequest request =
                memberLeaveRequestMapper.selectById(requestId);

        if (request == null
                || !projectId.equals(request.getProjectId())) {

            throw new BusinessException(
                    404,
                    "退出申请不存在"
            );
        }

        // 5. 必须还是 PENDING
        if (!"PENDING".equals(request.getStatus())) {

            throw new BusinessException(
                    409,
                    "该退出申请已经处理"
            );
        }

        // 6. 不能审批过期申请
        if (!request.getExpiresAt().isAfter(now)) {

            throw new BusinessException(
                    409,
                    "该退出申请已经过期"
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

        // ==========================
        // REJECT
        // ==========================

        if ("REJECT".equals(action)) {

            if (dto.getReviewNote() == null
                    || dto.getReviewNote().isBlank()) {

                throw new BusinessException(
                        400,
                        "拒绝退出申请时必须填写原因"
                );
            }

            int updated =
                    memberLeaveRequestMapper.reviewIfPending(
                            requestId,
                            projectId,
                            "REJECTED",
                            currentUser.getUserId(),
                            dto.getReviewNote().trim(),
                            now,
                            now
                    );

            if (updated != 1) {

                throw new BusinessException(
                        409,
                        "该退出申请已经处理或已经过期，请刷新后重试"
                );
            }

            operationLogService.record(
                    projectId,
                    currentUser.getUserId(),
                    "MEMBER_LEAVE_REQUEST",
                    requestId,
                    "REJECT_MEMBER_LEAVE_REQUEST",
                    "拒绝成员退出项目申请",
                    Map.of(
                            "status",
                            "PENDING"
                    ),
                    Map.of(
                            "status",
                            "REJECTED"
                    )
            );

            CreateNotificationDTO notification =
                    new CreateNotificationDTO();

            notification.setRecipientId(
                    request.getApplicantId()
            );

            notification.setActorId(
                    currentUser.getUserId()
            );

            notification.setType(
                    "MEMBER_LEAVE_REQUEST_REJECTED"
            );

            notification.setTitle(
                    "退出申请未通过"
            );

            notification.setContent(
                    "你的退出项目申请未通过"
            );

            notification.setProjectId(projectId);

            notification.setTargetType(
                    "MEMBER_LEAVE_REQUEST"
            );

            notification.setTargetId(requestId);

            notificationService.createNotification(
                    notification
            );

            return;
        }

        // ==========================
        // APPROVE
        // ==========================

        // 7. 申请人必须还是 ACTIVE DEVELOPER
        ProjectMember applicantMember =
                projectMemberMapper.selectOne(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(
                                        ProjectMember::getProjectId,
                                        projectId
                                )
                                .eq(
                                        ProjectMember::getUserId,
                                        request.getApplicantId()
                                )
                                .eq(
                                        ProjectMember::getRole,
                                        "DEVELOPER"
                                )
                                .eq(
                                        ProjectMember::getStatus,
                                        "ACTIVE"
                                )
                );

        if (applicantMember == null) {

            throw new BusinessException(
                    409,
                    "申请人已不是当前项目的有效开发成员"
            );
        }

        // 8. 再次检查有没有未完成任务
        Long unfinishedTaskCount =
                taskMapper.selectCount(
                        new LambdaQueryWrapper<Task>()
                                .eq(
                                        Task::getProjectId,
                                        projectId
                                )
                                .eq(
                                        Task::getAssigneeId,
                                        request.getApplicantId()
                                )
                                .in(
                                        Task::getStatus,
                                        Set.of(
                                                "TODO",
                                                "IN_PROGRESS",
                                                "REVIEW"
                                        )
                                )
                                .isNull(
                                        Task::getDeletedAt
                                )
                );

        if (unfinishedTaskCount > 0) {

            throw new BusinessException(
                    409,
                    "该成员仍有未完成任务，暂时不能退出项目"
            );
        }

        // 9. 先把退出申请改成 APPROVED
        int reviewed =
                memberLeaveRequestMapper.reviewIfPending(
                        requestId,
                        projectId,
                        "APPROVED",
                        currentUser.getUserId(),
                        dto.getReviewNote(),
                        now,
                        now
                );

        if (reviewed != 1) {

            throw new BusinessException(
                    409,
                    "该退出申请已经处理或已经过期，请刷新后重试"
            );
        }

        // 10. 真正让开发者退出项目
        int memberUpdated =
                projectMemberMapper.deactivateDeveloperIfActive(
                        projectId,
                        request.getApplicantId(),
                        now
                );

        if (memberUpdated != 1) {

            throw new BusinessException(
                    409,
                    "成员状态已发生变化，请刷新后重试"
            );
        }

        // 11. 自动取消该成员自己的 PENDING Task Request
        taskRequestMapper.cancelPendingByRequester(
                projectId,
                request.getApplicantId(),
                now
        );

        // 12. 写 Operation Log
        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "MEMBER_LEAVE_REQUEST",
                requestId,
                "APPROVE_MEMBER_LEAVE_REQUEST",
                "批准成员退出项目申请",
                Map.of(
                        "status",
                        "PENDING"
                ),
                Map.of(
                        "status",
                        "APPROVED",
                        "memberStatus",
                        "INACTIVE"
                )
        );

        // 13. 通知退出成员
        CreateNotificationDTO notification =
                new CreateNotificationDTO();

        notification.setRecipientId(
                request.getApplicantId()
        );

        notification.setActorId(
                currentUser.getUserId()
        );

        notification.setType(
                "MEMBER_LEAVE_REQUEST_APPROVED"
        );

        notification.setTitle(
                "退出申请已通过"
        );

        notification.setContent(
                "你的退出项目申请已通过，你已退出该项目"
        );

        notification.setProjectId(projectId);

        notification.setTargetType(
                "MEMBER_LEAVE_REQUEST"
        );

        notification.setTargetId(requestId);

        notificationService.createNotification(
                notification
        );
    }
}