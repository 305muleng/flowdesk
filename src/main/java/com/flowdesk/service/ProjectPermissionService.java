package com.flowdesk.service;

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

        ProjectMember member = projectMemberMapper.selectOne(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, currentUser.getUserId())
                        .eq(ProjectMember::getStatus, "ACTIVE")
        );

        if (member == null) {
            throw new BusinessException(403, "你不是该项目的有效成员");
        }

        return member;
    }

    public ProjectMember requireProjectManager(Long projectId) {

        ProjectMember member = requireProjectMember(projectId);

        if (!"PROJECT_MANAGER".equals(member.getRole())) {
            throw new BusinessException(403, "你不是该项目负责人");
        }

        return member;
    }
}