package com.flowdesk.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.enums.TaskStatus;
import com.flowdesk.model.Task;

/** 统一未完成任务的查询范围；调用方负责权限、加锁和检查时机。 */
public final class TaskQueryConditions {

    private TaskQueryConditions() {}

    public static LambdaQueryWrapper<Task> unfinishedInProject(Long projectId) {
        return new LambdaQueryWrapper<Task>()
            .eq(Task::getProjectId, projectId)
            .in(Task::getStatus, TaskStatus.unfinishedStatuses())
            .isNull(Task::getDeletedAt);
    }

    public static LambdaQueryWrapper<Task> unfinishedForAssignee(Long projectId, Long assigneeId) {
        // 每次创建新的条件对象，避免不同项目或成员之间共享可变查询条件。
        return unfinishedInProject(projectId).eq(Task::getAssigneeId, assigneeId);
    }
}
