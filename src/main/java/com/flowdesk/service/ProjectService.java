package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CancelProjectDTO;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.CreateProjectDTO;
import com.flowdesk.dto.RemoveProjectMemberDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.model.User;
import com.flowdesk.vo.ProjectMemberVO;
import com.flowdesk.vo.ProjectVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectPermissionService projectPermissionService;
    private final OperationLogService operationLogService;
    private final TaskMapper taskMapper;
    private final TaskRequestMapper taskRequestMapper;
    private final MemberLeaveRequestMapper memberLeaveRequestMapper;
    private final NotificationService notificationService;
    private final UserMapper userMapper;

    public ProjectService(
            ProjectMapper projectMapper,
            ProjectMemberMapper projectMemberMapper,
            ProjectPermissionService projectPermissionService,
            OperationLogService operationLogService,
            TaskMapper taskMapper,
            TaskRequestMapper taskRequestMapper,
            MemberLeaveRequestMapper memberLeaveRequestMapper,
            NotificationService notificationService,
            UserMapper userMapper
            ) {

        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectPermissionService = projectPermissionService;
        this.operationLogService = operationLogService;
        this.taskMapper = taskMapper;
        this.taskRequestMapper = taskRequestMapper;
        this.memberLeaveRequestMapper = memberLeaveRequestMapper;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
    }

    @Transactional
    public Long createProject(CreateProjectDTO dto) {

        CurrentUser currentUser = UserContext.get();

        User creator =
                userMapper.selectByIdForUpdate(
                        currentUser.getUserId()
                );

        if (creator == null) {
            throw new BusinessException(
                    401,
                    "当前用户不存在"
            );
        }

        if (!"ACTIVE".equals(creator.getStatus())) {
            throw new BusinessException(
                    403,
                    "账号已被禁用"
            );
        }

        if (!"USER".equals(creator.getSystemRole())) {
            throw new BusinessException(
                    403,
                    "系统管理员不能创建项目"
            );
        }

        Project project = new Project();

        project.setCreatorId(currentUser.getUserId());
        project.setName(dto.getName().trim());
        project.setDescription(dto.getDescription());
        project.setGoal(dto.getGoal());
        project.setExpectedEndTime(dto.getExpectedEndTime());
        project.setStatus("PREPARING");

        projectMapper.insert(project);

        ProjectMember projectMember = new ProjectMember();

        projectMember.setProjectId(project.getId());
        projectMember.setUserId(currentUser.getUserId());
        projectMember.setRole("PROJECT_MANAGER");
        projectMember.setStatus("ACTIVE");

        projectMemberMapper.insert(projectMember);

        Map<String, Object> afterData = new HashMap<>();

        afterData.put(
                "name",
                project.getName()
        );

        afterData.put(
                "status",
                project.getStatus()
        );

        afterData.put(
                "creatorId",
                currentUser.getUserId()
        );

        operationLogService.record(
                project.getId(),
                currentUser.getUserId(),
                "PROJECT",
                project.getId(),
                "CREATE_PROJECT",
                "创建项目",
                null,
                afterData
        );

        return project.getId();
    }

    public List<ProjectVO> getMyProjects() {
        if ("SYSTEM_ADMIN".equals(UserContext.get().getSystemRole())) {
            return List.of();
        }
        return projectMapper.selectProjectsForUser(UserContext.get().getUserId());
    }

    public ProjectVO getProjectDetail(Long projectId) {

        projectPermissionService.requireProjectMember(projectId);

        Project project = projectMapper.selectById(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        ProjectVO vo = new ProjectVO();

        vo.setId(project.getId());
        vo.setCreatorId(project.getCreatorId());
        vo.setName(project.getName());
        vo.setDescription(project.getDescription());
        vo.setGoal(project.getGoal());
        vo.setStatus(project.getStatus());
        vo.setStartTime(project.getStartTime());
        vo.setExpectedEndTime(project.getExpectedEndTime());
        vo.setActualEndTime(project.getActualEndTime());
        vo.setCreatedAt(project.getCreatedAt());
        vo.setUpdatedAt(project.getUpdatedAt());

        return vo;
    }

    public List<ProjectMemberVO> getProjectMembers(Long projectId) {
        projectPermissionService.requireProjectMember(projectId);
        return projectMemberMapper.selectActiveMembers(projectId);
    }

    @Transactional
    public void startProject(Long projectId) {

        // 1. 只有项目负责人可以启动项目
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

        // 3. 只有准备中的项目可以启动
        if (!"PREPARING".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "只有准备中的项目可以启动"
            );
        }

        CurrentUser currentUser =
                UserContext.get();

        LocalDateTime now =
                LocalDateTime.now();

        // 4. 保存修改前状态
        String oldStatus =
                project.getStatus();

        // 5. 修改项目
        project.setStatus("IN_PROGRESS");
        project.setStartTime(now);
        project.setUpdatedAt(now);

        int updated = projectMapper.startIfPreparing(projectId, now);
        if (updated != 1) {
            throw new BusinessException(409, "项目状态已发生变化，请刷新后重试");
        }

        // 6. 准备修改前的数据
        Map<String, Object> beforeData =
                new HashMap<>();

        beforeData.put(
                "status",
                oldStatus
        );

        // 7. 准备修改后的数据
        Map<String, Object> afterData =
                new HashMap<>();

        afterData.put(
                "status",
                project.getStatus()
        );

        afterData.put(
                "startTime",
                project.getStartTime()
        );

        // 8. 写操作日志
        operationLogService.record(
                project.getId(),
                currentUser.getUserId(),
                "PROJECT",
                project.getId(),
                "START_PROJECT",
                "启动项目",
                beforeData,
                afterData
        );
    }

    @Transactional
    public void cancelProject(
            Long projectId,
            CancelProjectDTO dto) {

        // 1. 只有项目负责人可以取消项目
        projectPermissionService.requireProjectManager(projectId);

        // 2. 查询项目
        Project project = projectMapper.selectById(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        // 3. 只有准备中 / 进行中的项目可以取消
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许取消"
            );
        }

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        // 4. 保存旧状态
        String oldStatus = project.getStatus();

        // 5. 修改项目状态
        project.setStatus("CANCELLED");
        project.setCancelReason(
                dto.getReason().trim()
        );
        project.setCancelledAt(now);
        project.setActualEndTime(null);
        project.setUpdatedAt(now);

        int updated = projectMapper.cancelIfActive(projectId, dto.getReason().trim(), now);
        if (updated != 1) {
            throw new BusinessException(409, "项目状态已发生变化，请刷新后重试");
        }

        // 6. 审计日志
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
                project.getStatus()
        );

        afterData.put(
                "cancelReason",
                project.getCancelReason()
        );

        afterData.put(
                "cancelledAt",
                project.getCancelledAt()
        );

        operationLogService.record(
                project.getId(),
                currentUser.getUserId(),
                "PROJECT",
                project.getId(),
                "CANCEL_PROJECT",
                "取消项目",
                beforeData,
                afterData
        );
    }

    @Transactional
    public void archiveProject(Long projectId) {

        projectPermissionService.requireProjectManager(projectId);

        Project project = projectMapper.selectById(projectId);

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(
                    404,
                    "项目不存在"
            );
        }

        if (!"COMPLETED".equals(project.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有已完成的项目可以归档"
            );
        }

        CurrentUser currentUser = UserContext.get();
        LocalDateTime now = LocalDateTime.now();

        String oldStatus = project.getStatus();

        project.setStatus("ARCHIVED");
        project.setUpdatedAt(now);

        int updated = projectMapper.archiveIfCompleted(projectId, now);
        if (updated != 1) {
            throw new BusinessException(409, "项目状态已发生变化，请刷新后重试");
        }

        Map<String, Object> beforeData = new HashMap<>();
        beforeData.put("status", oldStatus);

        Map<String, Object> afterData = new HashMap<>();
        afterData.put("status", project.getStatus());

        operationLogService.record(
                project.getId(),
                currentUser.getUserId(),
                "PROJECT",
                project.getId(),
                "ARCHIVE_PROJECT",
                "归档项目",
                beforeData,
                afterData
        );
    }

    @Transactional
    public void removeProjectMember(
            Long projectId,
            Long userId,
            RemoveProjectMemberDTO dto) {

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

        // 3. 只有准备中 / 进行中可以变更成员
        if (!"PREPARING".equals(project.getStatus())
                && !"IN_PROGRESS".equals(project.getStatus())) {

            throw new BusinessException(
                    409,
                    "当前项目状态不允许移除成员"
            );
        }

        // 4. 查询目标成员
        ProjectMember targetMember =
                projectMemberMapper.selectOne(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(
                                        ProjectMember::getProjectId,
                                        projectId
                                )
                                .eq(
                                        ProjectMember::getUserId,
                                        userId
                                )
                                .eq(
                                        ProjectMember::getStatus,
                                        "ACTIVE"
                                )
                );

        if (targetMember == null) {

            throw new BusinessException(
                    404,
                    "该用户不是当前项目的有效成员"
            );
        }

        // 5. PM 不能通过这个接口删除自己或其他 PM
        if (!"DEVELOPER".equals(targetMember.getRole())) {

            throw new BusinessException(
                    409,
                    "只能直接移除开发人员"
            );
        }

        // 6. 检查未完成任务
        Long unfinishedTaskCount =
                taskMapper.selectCount(
                        new LambdaQueryWrapper<Task>()
                                .eq(
                                        Task::getProjectId,
                                        projectId
                                )
                                .eq(
                                        Task::getAssigneeId,
                                        userId
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
                    "该成员仍有未完成任务，请先完成或重新分配任务"
            );
        }

        // 7. 真正移除成员
        int memberUpdated =
                projectMemberMapper.deactivateDeveloperIfActive(
                        projectId,
                        userId,
                        now
                );

        if (memberUpdated != 1) {

            throw new BusinessException(
                    409,
                    "成员状态已发生变化，请刷新后重试"
            );
        }

        // 8. 自动取消该成员自己的 PENDING Task Request
        taskRequestMapper.cancelPendingByRequester(
                projectId,
                userId,
                now
        );

        // 9. 如果他自己还有 PENDING 退出申请，也结束掉
        memberLeaveRequestMapper.cancelPendingByApplicant(
                projectId,
                userId,
                now
        );

        // 10. 处理可选原因
        String reason = null;

        if (dto != null
                && dto.getReason() != null
                && !dto.getReason().isBlank()) {

            reason = dto.getReason().trim();
        }

        // 11. Operation Log
        operationLogService.record(
                projectId,
                currentUser.getUserId(),
                "PROJECT_MEMBER",
                targetMember.getId(),
                "REMOVE_PROJECT_MEMBER",
                reason == null
                        ? "移除项目成员"
                        : "移除项目成员，原因：" + reason,
                Map.of(
                        "status",
                        "ACTIVE",
                        "role",
                        "DEVELOPER"
                ),
                Map.of(
                        "status",
                        "INACTIVE",
                        "role",
                        "DEVELOPER"
                )
        );

        // 12. 通知被移除的成员
        CreateNotificationDTO notification =
                new CreateNotificationDTO();

        notification.setRecipientId(userId);
        notification.setActorId(
                currentUser.getUserId()
        );

        notification.setType(
                "PROJECT_MEMBER_REMOVED"
        );

        notification.setTitle(
                "你已被移出项目"
        );

        notification.setContent(
                reason == null
                        ? "项目负责人已将你移出项目"
                        : "项目负责人已将你移出项目，原因：" + reason
        );

        notification.setProjectId(projectId);

        notification.setTargetType(
                "PROJECT_MEMBER"
        );

        notification.setTargetId(
                targetMember.getId()
        );

        notificationService.createNotification(
                notification
        );
    }
}
