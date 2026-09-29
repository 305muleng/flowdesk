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
import com.flowdesk.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

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
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectAcceptanceService(
            ProjectAcceptanceMapper projectAcceptanceMapper,
            ProjectMapper projectMapper,
            TaskMapper taskMapper,
            ProjectPermissionService projectPermissionService,
            OperationLogService operationLogService,
            TaskRequestMapper taskRequestMapper,
            UserMapper userMapper,
            NotificationService notificationService,
            ProjectMemberMapper projectMemberMapper) {

        this.projectAcceptanceMapper = projectAcceptanceMapper;
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.projectPermissionService = projectPermissionService;
        this.operationLogService = operationLogService;
        this.taskRequestMapper = taskRequestMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
        this.projectMemberMapper = projectMemberMapper;
    }

    @Transactional
    public Long submitAcceptance(
            Long projectId,
            SubmitProjectAcceptanceDTO dto) {

        // 1. 只有项目负责人可以提交项目验收
        projectPermissionService.requireProjectManager(projectId);

        Project project = projectMapper.selectByIdForUpdate(projectId);

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

        String repositoryUrl = trimToNull(dto.getRepositoryUrl());
        String deployUrl = trimToNull(dto.getDeployUrl());
        String documentUrl = trimToNull(dto.getDocumentUrl());

        if (repositoryUrl == null
                && deployUrl == null
                && documentUrl == null) {

            throw new BusinessException(
                    400,
                    "提交项目验收时至少需要填写一个交付链接"
            );
        }

        validateDeliveryUrl(repositoryUrl, "代码仓库");
        validateDeliveryUrl(deployUrl, "部署地址");
        validateDeliveryUrl(documentUrl, "项目文档");

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

        int transitioned = projectMapper.submitAcceptanceIfInProgress(
                projectId,
                repositoryUrl,
                deployUrl,
                documentUrl,
                now
        );
        if (transitioned != 1) {
            throw new BusinessException(409, "项目状态已发生变化，请刷新后重试");
        }

        projectAcceptanceMapper.insert(acceptance);

// 6. 项目进入待验收状态（数据库已通过条件更新完成）
        project.setStatus("PENDING_ACCEPTANCE");
        project.setUpdatedAt(now);


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

        requireSystemAdmin();

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
        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }
        return projectAcceptanceMapper.selectProjectAcceptances(projectId);
    }

    @Transactional
    public void reviewAcceptance(
            Long acceptanceId,
            ReviewProjectAcceptanceDTO dto) {

        requireSystemAdmin();

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
                projectMapper.selectByIdForUpdate(
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

        if ("REJECT".equals(action)
                && (dto.getReviewNote() == null || dto.getReviewNote().isBlank())) {
            throw new BusinessException(400, "驳回项目验收时必须填写原因");
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

        // 条件状态更新保证并发 approve/reject 只能成功一个
        int projectUpdated = projectMapper.reviewAcceptanceIfPending(
                project.getId(), project.getStatus(), project.getActualEndTime(), now);
        if (projectUpdated != 1) {
            throw new BusinessException(409, "项目验收状态已发生变化，请刷新后重试");
        }
        int acceptanceUpdated = projectAcceptanceMapper.reviewIfPending(
                acceptance.getId(), project.getId(), acceptance.getReviewStatus(),
                currentUser.getUserId(), dto.getReviewNote(), now);
        if (acceptanceUpdated != 1) {
            throw new BusinessException(409, "该项目验收已经处理，请刷新后重试");
        }

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

        List<ProjectMemberVO> managers = projectMemberMapper.selectActiveMembers(project.getId());
        for (ProjectMemberVO manager : managers) {
            if (!"PROJECT_MANAGER".equals(manager.getRole())
                    || manager.getUserId().equals(currentUser.getUserId())) {
                continue;
            }

            CreateNotificationDTO notificationDTO =
                    new CreateNotificationDTO();

            notificationDTO.setRecipientId(manager.getUserId());
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

    private void requireSystemAdmin() {
        CurrentUser currentUser = UserContext.get();
        if (currentUser == null || !"SYSTEM_ADMIN".equals(currentUser.getSystemRole())) {
            throw new BusinessException(403, "无系统管理员权限");
        }
    }

    @Transactional(readOnly = true)
    public ProjectAcceptanceDetailVO getAcceptanceDetail(
            Long acceptanceId) {

        requireSystemAdmin();

        // 1. 验收 + 项目基本信息
        ProjectAcceptanceDetailVO detail =
                projectAcceptanceMapper
                        .selectAcceptanceDetail(acceptanceId);

        if (detail == null) {
            throw new BusinessException(
                    404,
                    "项目验收记录不存在"
            );
        }

        Long projectId = detail.getProjectId();

        // 2. 一次查出项目所有有效任务
        List<Task> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getProjectId, projectId)
                        .isNull(Task::getDeletedAt)
        );

        int completedTaskCount = 0;
        int cancelledTaskCount = 0;

        // 每个人完成了哪些任务
        Map<Long, List<TaskSimpleVO>> completedTasksByUser =
                new HashMap<>();

        // 暂时保存取消任务
        List<Task> cancelledTaskEntities =
                new ArrayList<>();

        for (Task task : tasks) {

            if ("DONE".equals(task.getStatus())) {

                completedTaskCount++;

                if (task.getAssigneeId() != null) {

                    TaskSimpleVO taskVO =
                            new TaskSimpleVO();

                    taskVO.setTaskId(task.getId());
                    taskVO.setTitle(task.getTitle());
                    taskVO.setCompletedAt(
                            task.getCompletedAt()
                    );

                    completedTasksByUser
                            .computeIfAbsent(
                                    task.getAssigneeId(),
                                    key -> new ArrayList<>()
                            )
                            .add(taskVO);
                }

            } else if ("CANCELLED".equals(
                    task.getStatus())) {

                cancelledTaskCount++;
                cancelledTaskEntities.add(task);
            }
        }

        // 3. 任务统计
        int totalTaskCount = tasks.size();

        detail.setTotalTaskCount(totalTaskCount);
        detail.setCompletedTaskCount(
                completedTaskCount
        );
        detail.setCancelledTaskCount(
                cancelledTaskCount
        );

        int rateDenominator = completedTaskCount + cancelledTaskCount;
        double completionRate =
                rateDenominator == 0
                        ? 0.0
                        : Math.round(
                        completedTaskCount
                                * 10000.0
                                / rateDenominator
                ) / 100.0;

        detail.setCompletionRate(completionRate);

        // 4. 查询历史项目成员
        List<MemberAcceptanceVO> members =
                projectMemberMapper
                        .selectAcceptanceMembers(projectId);

        Map<Long, String> memberNames =
                new HashMap<>();

        for (MemberAcceptanceVO member : members) {

            List<TaskSimpleVO> memberTasks =
                    completedTasksByUser.getOrDefault(
                            member.getUserId(),
                            List.of()
                    );

            member.setCompletedTasks(memberTasks);

            member.setCompletedTaskCount(
                    memberTasks.size()
            );

            memberNames.put(
                    member.getUserId(),
                    member.getUserName()
            );
        }

        detail.setMembers(members);

        // 5. 组装取消任务
        List<CancelledTaskVO> cancelledTasks =
                new ArrayList<>();

        for (Task task : cancelledTaskEntities) {

            CancelledTaskVO taskVO =
                    new CancelledTaskVO();

            taskVO.setTaskId(task.getId());
            taskVO.setTitle(task.getTitle());
            taskVO.setCancelReason(
                    task.getCancelReason()
            );
            taskVO.setCancelledAt(
                    task.getCancelledAt()
            );

            taskVO.setAssigneeId(
                    task.getAssigneeId()
            );

            if (task.getAssigneeId() != null) {
                taskVO.setAssigneeName(
                        memberNames.get(
                                task.getAssigneeId()
                        )
                );
            }

            cancelledTasks.add(taskVO);
        }

        detail.setCancelledTasks(cancelledTasks);

        // 6. 历次验收记录
        detail.setAcceptanceHistory(
                projectAcceptanceMapper
                        .selectProjectAcceptances(
                                projectId
                        )
        );

        return detail;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private void validateDeliveryUrl(String value, String label) {
        if (value == null) {
            return;
        }
        if (!isValidHttpUrl(value)) {
            throw new BusinessException(400, label + "必须是有效的 http:// 或 https:// 链接");
        }
    }

    private boolean isValidHttpUrl(String value) {
        int schemeLength;
        if (value.regionMatches(true, 0, "https://", 0, 8)) {
            schemeLength = 8;
        } else if (value.regionMatches(true, 0, "http://", 0, 7)) {
            schemeLength = 7;
        } else {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)
                    || Character.isISOControl(ch) || ch == '\\'
                    || ch == '"' || ch == '<' || ch == '>') {
                return false;
            }
            if (ch == '%' && (i + 2 >= value.length()
                    || !isAsciiHex(value.charAt(i + 1))
                    || !isAsciiHex(value.charAt(i + 2)))) {
                return false;
            }
        }

        int authorityEnd = value.length();
        for (int i = schemeLength; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = value.substring(schemeLength, authorityEnd);
        if (authority.isEmpty()) {
            return false;
        }

        String host;
        String port = null;
        if (authority.startsWith("[")) {
            int closing = authority.indexOf(']');
            if (closing < 0) {
                return false;
            }
            host = authority.substring(1, closing);
            if (!host.contains(":") || !host.matches("[0-9A-Fa-f:.]+")) {
                return false;
            }
            try {
                InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                return false;
            }
            if (closing + 1 < authority.length()) {
                if (authority.charAt(closing + 1) != ':') {
                    return false;
                }
                port = authority.substring(closing + 2);
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                if (colon != authority.lastIndexOf(':')) {
                    return false;
                }
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            } else {
                host = authority;
            }
            if (!isValidAsciiHost(host)) {
                return false;
            }
        }

        if (port != null) {
            if (!port.matches("[0-9]+")) {
                return false;
            }
            try {
                int number = Integer.parseInt(port);
                return number >= 1 && number <= 65535;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidAsciiHost(String host) {
        if (host.isEmpty() || host.length() > 253 || !host.chars().allMatch(ch -> ch < 128)) {
            return false;
        }
        if (host.matches("[0-9.]+")) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}")
                        || Integer.parseInt(octet) > 255) {
                    return false;
                }
            }
            return true;
        }
        for (String label : host.split("\\.", -1)) {
            if (!label.matches("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                return false;
            }
        }
        return true;
    }

    private boolean isAsciiHex(char ch) {
        return (ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'f')
                || (ch >= 'A' && ch <= 'F');
    }
}
