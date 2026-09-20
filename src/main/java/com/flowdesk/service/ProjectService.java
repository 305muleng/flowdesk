package com.flowdesk.service;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CancelProjectDTO;
import com.flowdesk.dto.CreateProjectDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.vo.ProjectMemberVO;
import com.flowdesk.vo.ProjectVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectPermissionService projectPermissionService;
    private final OperationLogService operationLogService;

    public ProjectService(
            ProjectMapper projectMapper,
            ProjectMemberMapper projectMemberMapper,
            ProjectPermissionService projectPermissionService,
            OperationLogService operationLogService) {

        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectPermissionService = projectPermissionService;
        this.operationLogService = operationLogService;
    }

    @Transactional
    public Long createProject(CreateProjectDTO dto) {

        CurrentUser currentUser = UserContext.get();

        if ("SYSTEM_ADMIN".equals(currentUser.getSystemRole())) {
            throw new BusinessException(403, "系统管理员不能创建项目");
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

        projectMapper.updateById(project);

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

        projectMapper.updateById(project);

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

        projectMapper.updateById(project);

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
}
