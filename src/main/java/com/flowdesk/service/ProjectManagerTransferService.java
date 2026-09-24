package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.CreateProjectManagerTransferDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.vo.ProjectManagerTransferVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectManagerTransferService {

    private final ProjectManagerTransferMapper transferMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final TaskMapper taskMapper;
    private final ProjectPermissionService projectPermissionService;
    private final NotificationService notificationService;
    private final OperationLogService operationLogService;
    private final PasswordEncoder passwordEncoder;
    private final int expireDays;
    private final ProjectMemberMapper projectMemberMapper;
    private final TaskRequestMapper taskRequestMapper;
    private final MemberLeaveRequestMapper memberLeaveRequestMapper;

    public ProjectManagerTransferService(
            ProjectManagerTransferMapper transferMapper,
            ProjectMapper projectMapper,
            UserMapper userMapper,
            TaskMapper taskMapper,
            ProjectPermissionService projectPermissionService,
            NotificationService notificationService,
            OperationLogService operationLogService,
            PasswordEncoder passwordEncoder,
            ProjectMemberMapper projectMemberMapper,
            TaskRequestMapper taskRequestMapper,
            MemberLeaveRequestMapper memberLeaveRequestMapper,
            @Value("${project-manager-transfer.expire-days}")
            int expireDays) {

        this.transferMapper = transferMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.taskMapper = taskMapper;
        this.projectPermissionService = projectPermissionService;
        this.notificationService = notificationService;
        this.operationLogService = operationLogService;
        this.passwordEncoder = passwordEncoder;
        this.expireDays = expireDays;
        this.projectMemberMapper = projectMemberMapper;
        this.taskRequestMapper = taskRequestMapper;
        this.memberLeaveRequestMapper = memberLeaveRequestMapper;
    }

    @Transactional
    public Long createTransfer(
            Long projectId,
            CreateProjectManagerTransferDTO dto) {

        // 1. 当前操作人必须是项目负责人
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

        // 3. 只有 PREPARING / IN_PROGRESS 可以转让
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许转让项目负责人"
            );
        }

        // 4. 再次确认当前负责人密码
        User currentManager =
                userMapper.selectById(
                        currentUser.getUserId()
                );

        if (currentManager == null) {
            throw new BusinessException(
                    401,
                    "当前登录用户不存在"
            );
        }

        boolean passwordCorrect =
                passwordEncoder.matches(
                        dto.getCurrentPassword(),
                        currentManager.getPasswordHash()
                );

        if (!passwordCorrect) {
            throw new BusinessException(
                    403,
                    "当前密码错误"
            );
        }

        // 5. 验证旧负责人转让成功后的处理方式
        String oldManagerAction =
                dto.getOldManagerAction()
                        .trim()
                        .toUpperCase();

        if (!"STAY".equals(oldManagerAction)
                && !"LEAVE".equals(oldManagerAction)) {

            throw new BusinessException(
                    400,
                    "原负责人处理方式只能是 STAY 或 LEAVE"
            );
        }

        // 6. 查询目标用户
        String targetUsername =
                dto.getTargetUsername().trim();

        User targetUser =
                userMapper.selectOne(
                        new LambdaQueryWrapper<User>()
                                .eq(
                                        User::getUsername,
                                        targetUsername
                                )
                );

        if (targetUser == null) {
            throw new BusinessException(
                    404,
                    "目标用户不存在"
            );
        }

        // 7. 不能转让给自己
        if (currentUser.getUserId()
                .equals(targetUser.getId())) {

            throw new BusinessException(
                    400,
                    "不能将项目负责人转让给自己"
            );
        }

        // 8. 目标账号必须正常
        if (!"ACTIVE".equals(targetUser.getStatus())) {

            throw new BusinessException(
                    409,
                    "目标用户账号不可用"
            );
        }

        // 9. 系统管理员不能成为项目负责人
        if (!"USER".equals(targetUser.getSystemRole())) {

            throw new BusinessException(
                    403,
                    "系统管理员不能成为项目负责人"
            );
        }

        // 10. 一个项目同时最多一个有效 PENDING 转让
        Long pendingCount =
                transferMapper.selectCount(
                        new LambdaQueryWrapper<ProjectManagerTransfer>()
                                .eq(
                                        ProjectManagerTransfer::getProjectId,
                                        projectId
                                )
                                .eq(
                                        ProjectManagerTransfer::getStatus,
                                        "PENDING"
                                )
                                .gt(
                                        ProjectManagerTransfer::getExpiresAt,
                                        now
                                )
                );

        if (pendingCount > 0) {

            throw new BusinessException(
                    409,
                    "该项目已经存在待处理的负责人转让"
            );
        }

        // 11. 如果原负责人选择转让后退出，
        // 必须保证自己没有未完成任务
        if ("LEAVE".equals(oldManagerAction)) {

            Long unfinishedTaskCount =
                    taskMapper.selectCount(
                            new LambdaQueryWrapper<Task>()
                                    .eq(
                                            Task::getProjectId,
                                            projectId
                                    )
                                    .eq(
                                            Task::getAssigneeId,
                                            currentUser.getUserId()
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
                        "你仍有未完成任务，请先完成或重新分配后再选择退出项目"
                );
            }
        }

        // 12. 创建转让记录
        ProjectManagerTransfer transfer =
                new ProjectManagerTransfer();

        transfer.setProjectId(projectId);
        transfer.setFromUserId(
                currentUser.getUserId()
        );
        transfer.setToUserId(
                targetUser.getId()
        );
        transfer.setOldManagerAction(
                oldManagerAction
        );
        transfer.setStatus("PENDING");
        transfer.setCreatedAt(now);
        transfer.setExpiresAt(
                now.plusDays(expireDays)
        );

        transferMapper.insert(transfer);

        // 13. 写 Operation Log
        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "PROJECT_MANAGER_TRANSFER",
                transfer.getId(),
                "CREATE_MANAGER_TRANSFER",
                "发起项目负责人转让",
                null,
                Map.of(
                        "status", "PENDING",
                        "toUserId", targetUser.getId(),
                        "oldManagerAction", oldManagerAction
                )
        );

        // 14. 给目标用户发通知
        CreateNotificationDTO notification =
                new CreateNotificationDTO();

        notification.setRecipientId(
                targetUser.getId()
        );

        notification.setActorId(
                currentUser.getUserId()
        );

        notification.setType(
                "PROJECT_MANAGER_TRANSFER"
        );

        notification.setTitle(
                "项目负责人转让邀请"
        );

        notification.setContent(
                "项目“"
                        + project.getName()
                        + "”的负责人希望将项目负责人角色转让给你"
        );

        notification.setProjectId(projectId);

        notification.setTargetType(
                "PROJECT_MANAGER_TRANSFER"
        );

        notification.setTargetId(
                transfer.getId()
        );

        notificationService.createNotification(
                notification
        );

        return transfer.getId();
    }

    @Transactional
    public void acceptTransfer(
            Long projectId,
            Long transferId) {

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        // 1. 查询转让记录
        ProjectManagerTransfer transfer =
                transferMapper.selectById(transferId);

        if (transfer == null
                || !projectId.equals(
                transfer.getProjectId())) {

            throw new BusinessException(
                    404,
                    "负责人转让不存在"
            );
        }

        // 2. 只能由被转让人处理
        if (!currentUser.getUserId()
                .equals(transfer.getToUserId())) {

            throw new BusinessException(
                    403,
                    "你无权处理该负责人转让"
            );
        }

        // 3. 必须还是 PENDING
        if (!"PENDING".equals(
                transfer.getStatus())) {

            throw new BusinessException(
                    409,
                    "该负责人转让已经处理"
            );
        }

        // 4. 不能过期
        if (!transfer.getExpiresAt()
                .isAfter(now)) {

            throw new BusinessException(
                    409,
                    "该负责人转让已经过期"
            );
        }

        // 5. 锁项目
        Project project =
                projectMapper.selectByIdForUpdate(
                        projectId
                );

        if (project == null
                || project.getDeletedAt() != null) {

            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        // 6. 项目状态仍然必须允许负责人变更
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(
                project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许转让项目负责人"
            );
        }

        // 7. 再次检查目标用户账号
        User targetUser =
                userMapper.selectByIdForUpdate(
                        currentUser.getUserId()
                );

        if (targetUser == null) {

            throw new BusinessException(
                    401,
                    "当前登录用户不存在"
            );
        }

        if (!"ACTIVE".equals(
                targetUser.getStatus())) {

            throw new BusinessException(
                    403,
                    "账号已被禁用"
            );
        }

        if (!"USER".equals(
                targetUser.getSystemRole())) {

            throw new BusinessException(
                    403,
                    "系统管理员不能成为项目负责人"
            );
        }

        // 8. 如果旧负责人选择 LEAVE，
        // 接受时必须再次检查他的任务
        if ("LEAVE".equals(
                transfer.getOldManagerAction())) {

            Long unfinishedTaskCount =
                    taskMapper.selectCount(
                            new LambdaQueryWrapper<Task>()
                                    .eq(
                                            Task::getProjectId,
                                            projectId
                                    )
                                    .eq(
                                            Task::getAssigneeId,
                                            transfer.getFromUserId()
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
                        "原负责人新增了未完成任务，请先完成或重新分配后再接受转让"
                );
            }
        }

        // 9. 查询目标用户在项目中的历史成员关系
        ProjectMember targetMember =
                projectMemberMapper.selectOne(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(
                                        ProjectMember::getProjectId,
                                        projectId
                                )
                                .eq(
                                        ProjectMember::getUserId,
                                        currentUser.getUserId()
                                )
                );

        if (targetMember != null
                && "INACTIVE".equals(targetMember.getStatus())
                && targetMember.getLeftAt() != null
                && transfer.getCreatedAt() != null
                && targetMember.getLeftAt()
                .isAfter(transfer.getCreatedAt())) {

            throw new BusinessException(
                    409,
                    "目标用户在转让发起后已退出项目，该负责人转让已失效"
            );
        }

        // 10. 先正式把转让状态改成 ACCEPTED
        int accepted =
                transferMapper.acceptIfPending(
                        transferId,
                        projectId,
                        currentUser.getUserId(),
                        now,
                        now
                );

        if (accepted != 1) {

            throw new BusinessException(
                    409,
                    "负责人转让状态已发生变化，请刷新后重试"
            );
        }

        // 11. 处理新负责人
        if (targetMember == null) {

            // 从未加入过项目
            ProjectMember newManager =
                    new ProjectMember();

            newManager.setProjectId(projectId);
            newManager.setUserId(
                    currentUser.getUserId()
            );
            newManager.setRole(
                    "PROJECT_MANAGER"
            );
            newManager.setStatus("ACTIVE");
            newManager.setJoinedAt(now);

            projectMemberMapper.insert(
                    newManager
            );

        } else if ("INACTIVE".equals(
                targetMember.getStatus())) {

            // 历史成员重新加入并直接成为 PM
            int updated =
                    projectMemberMapper
                            .reactivateAsManager(
                                    targetMember.getId(),
                                    now
                            );

            if (updated != 1) {

                throw new BusinessException(
                        409,
                        "目标成员状态已发生变化，请刷新后重试"
                );
            }

        } else if ("ACTIVE".equals(
                targetMember.getStatus())
                && "DEVELOPER".equals(
                targetMember.getRole())) {

            // 当前 Developer 升级为 PM
            int updated =
                    projectMemberMapper
                            .promoteDeveloperToManager(
                                    projectId,
                                    currentUser.getUserId(),
                                    now
                            );

            if (updated != 1) {

                throw new BusinessException(
                        409,
                        "目标成员状态已发生变化，请刷新后重试"
                );
            }

        } else {

            throw new BusinessException(
                    409,
                    "目标用户当前项目角色异常，无法完成负责人转让"
            );
        }

        memberLeaveRequestMapper.cancelPendingByApplicant(
                projectId,
                currentUser.getUserId(),
                now
        );

        // 12. 处理旧负责人
        if ("STAY".equals(
                transfer.getOldManagerAction())) {

            int updated =
                    projectMemberMapper
                            .demoteManagerToDeveloper(
                                    projectId,
                                    transfer.getFromUserId(),
                                    now
                            );

            if (updated != 1) {

                throw new BusinessException(
                        409,
                        "原负责人状态已发生变化，请刷新后重试"
                );
            }

        } else {

            int updated =
                    projectMemberMapper
                            .deactivateManagerIfActive(
                                    projectId,
                                    transfer.getFromUserId(),
                                    now
                            );

            if (updated != 1) {
                throw new BusinessException(
                        409,
                        "原负责人状态已发生变化，请刷新后重试"
                );
            }

            taskRequestMapper.cancelPendingByRequester(
                    projectId,
                    transfer.getFromUserId(),
                    now
            );
        }

        // 13. Operation Log
        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "PROJECT_MANAGER_TRANSFER",
                transferId,
                "ACCEPT_MANAGER_TRANSFER",
                "接受项目负责人转让",
                Map.of(
                        "managerUserId",
                        transfer.getFromUserId()
                ),
                Map.of(
                        "managerUserId",
                        transfer.getToUserId(),
                        "oldManagerAction",
                        transfer.getOldManagerAction()
                )
        );

        // 14. 通知旧负责人
        CreateNotificationDTO notification =
                new CreateNotificationDTO();

        notification.setRecipientId(
                transfer.getFromUserId()
        );

        notification.setActorId(
                currentUser.getUserId()
        );

        notification.setType(
                "PROJECT_MANAGER_TRANSFER_ACCEPTED"
        );

        notification.setTitle(
                "负责人转让已接受"
        );

        notification.setContent(
                "你发起的项目“"
                        + project.getName()
                        + "”负责人转让已被接受"
        );

        notification.setProjectId(projectId);

        notification.setTargetType(
                "PROJECT_MANAGER_TRANSFER"
        );

        notification.setTargetId(
                transferId
        );

        notificationService.createNotification(
                notification
        );
    }

    @Transactional
    public void rejectTransfer(
            Long projectId,
            Long transferId) {

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        ProjectManagerTransfer transfer =
                transferMapper.selectById(transferId);

        if (transfer == null
                || !projectId.equals(
                transfer.getProjectId())) {

            throw new BusinessException(
                    404,
                    "负责人转让不存在"
            );
        }

        // 只能由目标用户拒绝
        if (!currentUser.getUserId()
                .equals(transfer.getToUserId())) {

            throw new BusinessException(
                    403,
                    "你无权处理该负责人转让"
            );
        }

        if (!"PENDING".equals(
                transfer.getStatus())) {

            throw new BusinessException(
                    409,
                    "该负责人转让已经处理"
            );
        }

        if (!transfer.getExpiresAt()
                .isAfter(now)) {

            throw new BusinessException(
                    409,
                    "该负责人转让已经过期"
            );
        }

        int updated =
                transferMapper.rejectIfPending(
                        transferId,
                        projectId,
                        currentUser.getUserId(),
                        now,
                        now
                );

        if (updated != 1) {

            throw new BusinessException(
                    409,
                    "负责人转让状态已发生变化，请刷新后重试"
            );
        }

        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "PROJECT_MANAGER_TRANSFER",
                transferId,
                "REJECT_MANAGER_TRANSFER",
                "拒绝项目负责人转让",
                Map.of(
                        "status", "PENDING"
                ),
                Map.of(
                        "status", "REJECTED"
                )
        );

        // 通知原负责人
        CreateNotificationDTO notification =
                new CreateNotificationDTO();

        notification.setRecipientId(
                transfer.getFromUserId()
        );

        notification.setActorId(
                currentUser.getUserId()
        );

        notification.setType(
                "PROJECT_MANAGER_TRANSFER_REJECTED"
        );

        notification.setTitle(
                "负责人转让已被拒绝"
        );

        notification.setContent(
                "你发起的项目负责人转让已被拒绝"
        );

        notification.setProjectId(projectId);
        notification.setTargetType(
                "PROJECT_MANAGER_TRANSFER"
        );
        notification.setTargetId(transferId);

        notificationService.createNotification(
                notification
        );
    }

    @Transactional
    public void cancelTransfer(
            Long projectId,
            Long transferId) {

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        ProjectManagerTransfer transfer =
                transferMapper.selectById(transferId);

        if (transfer == null
                || !projectId.equals(
                transfer.getProjectId())) {

            throw new BusinessException(
                    404,
                    "负责人转让不存在"
            );
        }

        // 必须是原来的发起人
        if (!currentUser.getUserId()
                .equals(transfer.getFromUserId())) {

            throw new BusinessException(
                    403,
                    "只能撤回自己发起的负责人转让"
            );
        }

        // 而且现在仍必须是项目负责人
        projectPermissionService
                .requireProjectManager(projectId);

        if (!"PENDING".equals(
                transfer.getStatus())) {

            throw new BusinessException(
                    409,
                    "该负责人转让已经处理"
            );
        }

        if (!transfer.getExpiresAt()
                .isAfter(now)) {

            throw new BusinessException(
                    409,
                    "该负责人转让已经过期"
            );
        }

        int updated =
                transferMapper.cancelIfPending(
                        transferId,
                        projectId,
                        currentUser.getUserId(),
                        now,
                        now
                );

        if (updated != 1) {

            throw new BusinessException(
                    409,
                    "负责人转让状态已发生变化，请刷新后重试"
            );
        }

        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "PROJECT_MANAGER_TRANSFER",
                transferId,
                "CANCEL_MANAGER_TRANSFER",
                "撤回项目负责人转让",
                Map.of(
                        "status", "PENDING"
                ),
                Map.of(
                        "status", "CANCELLED"
                )
        );

        // 通知原来的目标用户
        CreateNotificationDTO notification =
                new CreateNotificationDTO();

        notification.setRecipientId(
                transfer.getToUserId()
        );

        notification.setActorId(
                currentUser.getUserId()
        );

        notification.setType(
                "PROJECT_MANAGER_TRANSFER_CANCELLED"
        );

        notification.setTitle(
                "负责人转让已撤回"
        );

        notification.setContent(
                "项目负责人已撤回向你发起的负责人转让"
        );

        notification.setProjectId(projectId);
        notification.setTargetType(
                "PROJECT_MANAGER_TRANSFER"
        );
        notification.setTargetId(transferId);

        notificationService.createNotification(
                notification
        );
    }

    public ProjectManagerTransferVO getTransferDetail(
            Long projectId,
            Long transferId) {

        CurrentUser currentUser = UserContext.get();

        // 1. 先查询转让本身
        ProjectManagerTransferVO vo =
                transferMapper.selectDetail(
                        projectId,
                        transferId
                );

        if (vo == null) {

            throw new BusinessException(
                    404,
                    "负责人转让不存在"
            );
        }

        // 2. 判断是不是这次转让的当事人
        boolean isFromUser =
                currentUser.getUserId()
                        .equals(vo.getFromUserId());

        boolean isToUser =
                currentUser.getUserId()
                        .equals(vo.getToUserId());

        boolean isParticipant =
                isFromUser || isToUser;

        // 3. 当事人可以查看历史记录
        // 即使现在已经不是项目成员
        if (!isParticipant) {

            // 非当事人则必须是当前 PM
            projectPermissionService
                    .requireProjectManager(projectId);
        }

        // 4. 懒过期
        if ("PENDING".equals(vo.getStatus())
                && !vo.getExpiresAt()
                .isAfter(LocalDateTime.now())) {

            vo.setStatus("EXPIRED");
        }

        return vo;
    }
}