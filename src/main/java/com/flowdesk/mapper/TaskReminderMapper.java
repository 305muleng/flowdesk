package com.flowdesk.mapper;

import com.flowdesk.model.TaskReminderPlan;
import com.flowdesk.model.TaskReminderEvent;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskReminderMapper {
    // 仅在版本字段发生变化时推进版本；同一项目锁保护应用内的修改与消费。
    @Insert("""
        INSERT INTO task_reminder_plan (task_id, project_id, deadline, assignee_id, revision)
        VALUES (#{taskId}, #{projectId}, #{deadline}, #{assigneeId}, 1)
        ON DUPLICATE KEY UPDATE
          revision = revision + IF(NOT (deadline <=> VALUES(deadline))
                    OR NOT (assignee_id <=> VALUES(assignee_id)), 1, 0),
          deadline = VALUES(deadline), assignee_id = VALUES(assignee_id)
        """)
    int syncPlan(@Param("taskId") Long taskId, @Param("projectId") Long projectId,
                 @Param("deadline") LocalDateTime deadline, @Param("assigneeId") Long assigneeId);

    @Select("SELECT task_id, project_id, deadline, assignee_id, revision FROM task_reminder_plan WHERE task_id = #{id} FOR UPDATE")
    TaskReminderPlan lockPlan(@Param("id") Long id);

    // 恢复期间补齐历史任务或关闭模块期间改变的计划；已修复行不再进入本批次。
    @Select("""
        SELECT t.id FROM task t JOIN project p ON p.id = t.project_id
        LEFT JOIN task_reminder_plan r ON r.task_id = t.id
        WHERE t.id > #{afterId} AND t.deleted_at IS NULL AND p.deleted_at IS NULL AND t.deadline IS NOT NULL
          AND p.status IN ('PREPARING', 'IN_PROGRESS') AND t.status IN ('TODO', 'IN_PROGRESS')
          AND (r.task_id IS NULL OR NOT (r.deadline <=> t.deadline) OR NOT (r.assignee_id <=> t.assignee_id))
        ORDER BY t.id LIMIT #{limit}
        """)
    List<Long> findUnplanned(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("""
        SELECT r.task_id FROM task_reminder_plan r JOIN task t ON t.id = r.task_id
        JOIN project p ON p.id = r.project_id
        WHERE r.task_id > #{afterId} AND p.status = 'IN_PROGRESS' AND p.deleted_at IS NULL AND t.deleted_at IS NULL
          AND t.status IN ('TODO', 'IN_PROGRESS') AND r.deadline <= #{windowEnd}
          AND r.deadline = t.deadline AND r.assignee_id <=> t.assignee_id
          AND NOT EXISTS (SELECT 1 FROM task_reminder_event e
              WHERE e.task_id = r.task_id AND e.plan_revision = r.revision
                AND e.kind = IF(r.deadline <= #{now}, 'OVERDUE', 'DUE_SOON')
                AND e.state IN ('PENDING', 'DONE'))
        ORDER BY r.task_id LIMIT #{limit}
        """)
    List<Long> findDue(@Param("now") LocalDateTime now, @Param("windowEnd") LocalDateTime windowEnd, @Param("afterId") long afterId, @Param("limit") int limit);

    @Insert("""
        INSERT INTO task_reminder_event (task_id, project_id, plan_revision, kind, state, next_publish_at, created_at)
        VALUES (#{taskId}, #{projectId}, #{revision}, #{kind}, 'PENDING', #{now}, #{now})
        ON DUPLICATE KEY UPDATE id = id
        """)
    int createEvent(@Param("taskId") Long taskId, @Param("projectId") Long projectId,
                    @Param("revision") long revision, @Param("kind") String kind, @Param("now") LocalDateTime now);

    // REVIEW 期间尚未发出的事件可在驳回后恢复；DONE 绝不能被重新置为待发送。
    @Update("""
        UPDATE task_reminder_event SET state = 'PENDING', next_publish_at = #{now},
            finished_at = NULL, last_error = NULL
        WHERE task_id = #{taskId} AND plan_revision = #{revision} AND kind = #{kind} AND state = 'CANCELLED'
        """)
    int reopenCancelled(@Param("taskId") Long taskId, @Param("revision") long revision,
                        @Param("kind") String kind, @Param("now") LocalDateTime now);

    String EVENT_COLUMNS = "id, task_id, project_id, plan_revision, kind, state, attempts";

    @Select("SELECT " + EVENT_COLUMNS + " FROM task_reminder_event WHERE id = #{id}")
    TaskReminderEvent findEvent(@Param("id") Long id);

    @Select("SELECT " + EVENT_COLUMNS + " FROM task_reminder_event WHERE id = #{id} FOR UPDATE")
    TaskReminderEvent lockEvent(@Param("id") Long id);

    @Select("SELECT " + EVENT_COLUMNS + " FROM task_reminder_event WHERE state = 'PENDING' AND next_publish_at <= #{now} ORDER BY next_publish_at, id LIMIT #{limit}")
    List<TaskReminderEvent> findPublishable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
        UPDATE task_reminder_event SET state = #{state}, finished_at = #{now}, last_error = NULL
        WHERE id = #{id} AND state = 'PENDING'
        """)
    int finish(@Param("id") Long id, @Param("state") String state, @Param("now") LocalDateTime now);

    // 先持久化下次投递时间，再发 Redis；进程中断或消息丢失后仍会重新投递。
    @Update("""
        UPDATE task_reminder_event SET next_publish_at = #{next}, attempts = attempts + 1
        WHERE id = #{id} AND state = 'PENDING' AND next_publish_at <= #{now}
        """)
    int reservePublish(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("next") LocalDateTime next);

    @Update("UPDATE task_reminder_event SET last_error = #{error} WHERE id = #{id} AND state = 'PENDING'")
    int recordFailure(@Param("id") Long id, @Param("error") String error);

    // 当前读并锁定有效接收人，避免把刚停用的账号当作可用执行人。
    @Select("""
        SELECT u.id FROM project_member pm JOIN `user` u ON u.id = pm.user_id
        WHERE pm.project_id = #{projectId} AND pm.user_id = #{userId} AND pm.status = 'ACTIVE'
          AND pm.role IN ('DEVELOPER', 'PROJECT_MANAGER') AND u.status = 'ACTIVE' AND u.system_role = 'USER'
        FOR UPDATE
        """)
    Long effectiveAssignee(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Select("""
        SELECT u.id FROM project_member pm JOIN `user` u ON u.id = pm.user_id
        WHERE pm.project_id = #{projectId} AND pm.status = 'ACTIVE' AND pm.role = 'PROJECT_MANAGER'
          AND u.status = 'ACTIVE' AND u.system_role = 'USER' LIMIT 1 FOR UPDATE
        """)
    Long effectiveManager(@Param("projectId") Long projectId);
}
