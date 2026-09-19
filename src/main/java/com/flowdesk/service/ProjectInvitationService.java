package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.SendInvitationDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectInvitationMapper;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectInvitation;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.User;
import com.flowdesk.vo.InvitationVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectInvitationService {

    private final ProjectInvitationMapper projectInvitationMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final ProjectPermissionService projectPermissionService;
    private final int expireDays;
    private final ProjectMapper projectMapper;
    private final NotificationService notificationService;

    public ProjectInvitationService(
            ProjectInvitationMapper projectInvitationMapper,
            ProjectMemberMapper projectMemberMapper,
            UserMapper userMapper,
            ProjectPermissionService projectPermissionService,
            ProjectMapper projectMapper,
            NotificationService notificationService,
            @Value("${invitation.expire-days}") int expireDays) {

        this.projectInvitationMapper = projectInvitationMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.userMapper = userMapper;
        this.projectPermissionService = projectPermissionService;
        this.expireDays = expireDays;
        this.projectMapper = projectMapper;
        this.notificationService = notificationService;
    }

    @Transactional
    public Long sendInvitation(Long projectId, SendInvitationDTO dto) {

        // 1. 当前用户必须是该项目负责人
        projectPermissionService.requireProjectManager(projectId);

        Project project =
                projectMapper.selectById(projectId);

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
                    "当前项目状态不允许发送邀请"
            );
        }

        CurrentUser currentUser = UserContext.get();

        String username = dto.getUsername().trim();

        // 2. 根据用户名查询被邀请人
        User invitee = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );

        if (invitee == null) {
            throw new BusinessException(404, "被邀请用户不存在");
        }

        // 3. 被邀请账号必须正常
        if (!"ACTIVE".equals(invitee.getStatus())) {
            throw new BusinessException(409, "被邀请用户账号不可用");
        }

        // 4. 不能邀请自己
        if (currentUser.getUserId().equals(invitee.getId())) {
            throw new BusinessException(400, "不能邀请自己加入项目");
        }

        // 5. 已经是有效成员，不能重复邀请
        ProjectMember existingMember = projectMemberMapper.selectOne(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, invitee.getId())
        );

        if (existingMember != null
                && "ACTIVE".equals(existingMember.getStatus())) {
            throw new BusinessException(409, "该用户已经是项目成员");
        }

        LocalDateTime now = LocalDateTime.now();

        // 6. 已经存在仍然有效的 PENDING 邀请，不能重复发送
        Long pendingCount = projectInvitationMapper.selectCount(
                new LambdaQueryWrapper<ProjectInvitation>()
                        .eq(ProjectInvitation::getProjectId, projectId)
                        .eq(ProjectInvitation::getInviteeId, invitee.getId())
                        .eq(ProjectInvitation::getStatus, "PENDING")
                        .gt(ProjectInvitation::getExpiresAt, now)
        );

        if (pendingCount > 0) {
            throw new BusinessException(409, "该用户已有待处理的有效邀请");
        }

        // 7. 创建新邀请
        ProjectInvitation invitation = new ProjectInvitation();

        invitation.setProjectId(projectId);
        invitation.setInviterId(currentUser.getUserId());
        invitation.setInviteeId(invitee.getId());
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(now.plusDays(expireDays));

        projectInvitationMapper.insert(invitation);

        CreateNotificationDTO notificationDTO =
                new CreateNotificationDTO();

        notificationDTO.setRecipientId(invitee.getId());
        notificationDTO.setActorId(currentUser.getUserId());

        notificationDTO.setType("PROJECT_INVITATION");
        notificationDTO.setTitle("项目邀请");
        notificationDTO.setContent(
                "你收到了项目“"
                        + project.getName()
                        + "”的加入邀请"
        );

        notificationDTO.setProjectId(projectId);
        notificationDTO.setTargetType("PROJECT_INVITATION");
        notificationDTO.setTargetId(invitation.getId());

        notificationService.createNotification(notificationDTO);

        return invitation.getId();
    }

    @Transactional
    public void acceptInvitation(Long invitationId) {

        CurrentUser currentUser = UserContext.get();

        // 1. 查询邀请
        ProjectInvitation invitation =
                projectInvitationMapper.selectById(invitationId);

        if (invitation == null) {
            throw new BusinessException(404, "邀请不存在");
        }

        // 2. 只能处理发给自己的邀请
        if (!currentUser.getUserId().equals(invitation.getInviteeId())) {
            throw new BusinessException(403, "无权处理该邀请");
        }

        // 3. 必须还是待处理状态
        if (!"PENDING".equals(invitation.getStatus())) {
            throw new BusinessException(409, "该邀请已经处理");
        }

        LocalDateTime now = LocalDateTime.now();

        // 4. 检查有没有过期
        if (!invitation.getExpiresAt().isAfter(now)) {
            throw new BusinessException(409, "该邀请已经过期");
        }

        Project project =
                projectMapper.selectById(
                        invitation.getProjectId()
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
                    "当前项目状态不允许接受邀请"
            );
        }

        // 5. 查询是否曾经是这个项目的成员
        ProjectMember member = projectMemberMapper.selectOne(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId,
                                invitation.getProjectId())
                        .eq(ProjectMember::getUserId,
                                currentUser.getUserId())
        );

        // 6. 第一次加入项目
        if (member == null) {

            member = new ProjectMember();

            member.setProjectId(invitation.getProjectId());
            member.setUserId(currentUser.getUserId());
            member.setRole("DEVELOPER");
            member.setStatus("ACTIVE");
            member.setJoinedAt(now);

            projectMemberMapper.insert(member);

        } else {

            // 7. 如果以前退出过，则重新激活
            member.setRole("DEVELOPER");
            member.setStatus("ACTIVE");
            member.setJoinedAt(now);
            member.setLeftAt(null);
            member.setUpdatedAt(now);

            projectMemberMapper.updateById(member);
        }

        // 8. 邀请变成已接受
        invitation.setStatus("ACCEPTED");
        invitation.setRespondedAt(now);

        projectInvitationMapper.updateById(invitation);

        CreateNotificationDTO notificationDTO =
                new CreateNotificationDTO();

        notificationDTO.setRecipientId(
                invitation.getInviterId()
        );

        notificationDTO.setActorId(
                currentUser.getUserId()
        );

        notificationDTO.setType(
                "PROJECT_INVITATION_ACCEPTED"
        );

        notificationDTO.setTitle(
                "项目邀请已接受"
        );

        notificationDTO.setContent(
                "你发送的项目邀请已被接受"
        );

        notificationDTO.setProjectId(
                invitation.getProjectId()
        );

        notificationDTO.setTargetType("PROJECT");
        notificationDTO.setTargetId(
                invitation.getProjectId()
        );

        notificationService.createNotification(
                notificationDTO
        );
    }

    public List<InvitationVO> getMyInvitations(String filter) {

        CurrentUser currentUser = UserContext.get();

        String normalizedFilter = filter.trim().toUpperCase();

        switch (normalizedFilter) {
            case "ALL", "PENDING", "ACCEPTED", "REJECTED", "EXPIRED":
                break;
            default:
                throw new BusinessException(400, "邀请筛选条件不正确");
        }

        LocalDateTime now = LocalDateTime.now();

        List<InvitationVO> invitations =
                projectInvitationMapper.selectMyInvitations(
                        currentUser.getUserId(),
                        normalizedFilter,
                        now
                );

        for (InvitationVO invitation : invitations) {

            boolean expired =
                    "PENDING".equals(invitation.getStatus())
                            && !invitation.getExpiresAt().isAfter(now);

            invitation.setExpired(expired);
        }

        return invitations;
    }

    @Transactional
    public void rejectInvitation(Long invitationId) {

        CurrentUser currentUser = UserContext.get();

        // 1. 查询邀请
        ProjectInvitation invitation =
                projectInvitationMapper.selectById(invitationId);

        if (invitation == null) {
            throw new BusinessException(404, "邀请不存在");
        }

        // 2. 只能拒绝发给自己的邀请
        if (!currentUser.getUserId().equals(invitation.getInviteeId())) {
            throw new BusinessException(403, "无权处理该邀请");
        }

        // 3. 必须还是待处理状态
        if (!"PENDING".equals(invitation.getStatus())) {
            throw new BusinessException(409, "该邀请已经处理");
        }

        LocalDateTime now = LocalDateTime.now();

        // 4. 已经过期的邀请不能再拒绝
        if (!invitation.getExpiresAt().isAfter(now)) {
            throw new BusinessException(409, "该邀请已经过期");
        }

        // 5. 修改邀请状态
        invitation.setStatus("REJECTED");
        invitation.setRespondedAt(now);

        projectInvitationMapper.updateById(invitation);

        CreateNotificationDTO notificationDTO =
                new CreateNotificationDTO();

        notificationDTO.setRecipientId(
                invitation.getInviterId()
        );

        notificationDTO.setActorId(
                currentUser.getUserId()
        );

        notificationDTO.setType(
                "PROJECT_INVITATION_REJECTED"
        );

        notificationDTO.setTitle(
                "项目邀请已拒绝"
        );

        notificationDTO.setContent(
                "你发送的项目邀请已被拒绝"
        );

        notificationDTO.setProjectId(
                invitation.getProjectId()
        );

        notificationDTO.setTargetType("PROJECT");
        notificationDTO.setTargetId(
                invitation.getProjectId()
        );

        notificationService.createNotification(
                notificationDTO
        );
    }
}