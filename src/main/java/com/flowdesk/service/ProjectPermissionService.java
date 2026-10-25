package com.flowdesk.service;

import com.flowdesk.enums.ProjectRole;
import com.flowdesk.enums.SystemRole;
import com.flowdesk.enums.ProjectMemberStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.model.ProjectMember;
import org.springframework.stereotype.Service;

@Service
public class ProjectPermissionService {

    private final ProjectMemberMapper projectMemberMapper;

    public ProjectPermissionService(ProjectMemberMapper projectMemberMapper) {
        this.projectMemberMapper = projectMemberMapper;
    }

    public ProjectMember requireProjectMember(Long projectId) {
        CurrentUser currentUser = UserContext.get();

        if (SystemRole.SYSTEM_ADMIN.matches(currentUser.getSystemRole())) {
            throw new BusinessException(403, "系统管理员不能参与普通项目");
        }

        ProjectMember member = projectMemberMapper.selectOne(
            new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, currentUser.getUserId())
                .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE)
        );

        if (member == null) {
            throw new BusinessException(403, "你不是该项目的有效成员");
        }

        return member;
    }

    public ProjectMember requireProjectManager(Long projectId) {
        ProjectMember member = requireProjectMember(projectId);

        if (!ProjectRole.PROJECT_MANAGER.matches(member.getRole())) {
            throw new BusinessException(403, "你不是该项目负责人");
        }

        return member;
    }

    /** 调用方先持有项目行锁，再读取当前成员关系，避免沿用等待锁之前的快照。 */
    public ProjectMember requireProjectMemberForUpdate(Long projectId) {
        CurrentUser currentUser = UserContext.get();

        if (currentUser == null || SystemRole.SYSTEM_ADMIN.matches(currentUser.getSystemRole())) {
            throw new BusinessException(403, "系统管理员不能参与普通项目");
        }

        ProjectMember member = projectMemberMapper.selectMemberForUpdate(projectId, currentUser.getUserId());

        if (member == null || !ProjectMemberStatus.ACTIVE.equals(member.getStatus())) {
            throw new BusinessException(403, "你不是该项目的有效成员");
        }

        return member;
    }

    public ProjectMember requireProjectManagerForUpdate(Long projectId) {
        ProjectMember member = requireProjectMemberForUpdate(projectId);
        if (!ProjectRole.PROJECT_MANAGER.matches(member.getRole())) {
            throw new BusinessException(403, "你不是该项目负责人");
        }
        return member;
    }
}
