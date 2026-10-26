package com.flowdesk.service;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.AddTaskCommentDTO;
import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.TaskCommentMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskComment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCommentService {
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final ProjectPermissionService projectPermissionService;
    private final TaskCommentMapper taskCommentMapper;

    public TaskCommentService(TaskMapper taskMapper, ProjectMapper projectMapper,
        ProjectPermissionService projectPermissionService, TaskCommentMapper taskCommentMapper) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.projectPermissionService = projectPermissionService;
        this.taskCommentMapper = taskCommentMapper;
    }

    @Transactional
    public Long addTaskComment(Long taskId, AddTaskCommentDTO dto) {
        // 1. 任务必须存在
        Task task = taskMapper.selectById(taskId);

        if (task == null || task.getDeletedAt() != null) {
            throw new BusinessException(404, "任务不存在");
        }

        // 2. 当前用户必须是该项目有效成员
        projectPermissionService.requireProjectMember(task.getProjectId());

        Project project = projectMapper.selectByIdForUpdate(task.getProjectId());

        if (project == null || project.getDeletedAt() != null) {
            throw new BusinessException(404, "项目不存在");
        }

        // 等待项目锁期间成员可能已退出或被移除，必须使用锁定读取重新授权。
        projectPermissionService.requireProjectMemberForUpdate(task.getProjectId());

        if (!ProjectStatus.PREPARING.equals(project.getStatus()) && !ProjectStatus.IN_PROGRESS.equals(project.getStatus())) {
            throw new BusinessException(409, "当前项目状态不允许新增评论");
        }

        // 3. 如果这是“回复”，检查被回复评论是否合法
        if (dto.getParentId() != null) {
            TaskComment parentComment = taskCommentMapper.selectById(dto.getParentId());

            if (parentComment == null || parentComment.getDeletedAt() != null) {
                throw new BusinessException(404, "被回复的评论不存在");
            }

            if (!taskId.equals(parentComment.getTaskId())) {
                throw new BusinessException(400, "不能回复其他任务的评论");
            }
        }

        CurrentUser currentUser = UserContext.get();

        // 4. 创建评论
        TaskComment comment = new TaskComment();

        comment.setTaskId(taskId);
        comment.setAuthorId(currentUser.getUserId());
        comment.setParentId(dto.getParentId());
        comment.setContent(dto.getContent().trim());

        taskCommentMapper.insert(comment);

        return comment.getId();
    }
}
