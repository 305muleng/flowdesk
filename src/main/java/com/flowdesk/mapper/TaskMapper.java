package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.Task;
import com.flowdesk.vo.TaskVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
    @Update("""
            UPDATE task
            SET status = #{newStatus}, completed_at = #{completedAt}, updated_at = #{updatedAt}
            WHERE id = #{taskId} AND status = #{expectedStatus} AND deleted_at IS NULL
            """)
    int transitionStatusIfCurrent(@Param("taskId") Long taskId,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("newStatus") String newStatus,
                                  @Param("completedAt") LocalDateTime completedAt,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE task
            SET status = #{newStatus}, updated_at = #{updatedAt}
            WHERE id = #{taskId} AND status = #{expectedStatus}
              AND assignee_id = #{assigneeId} AND deleted_at IS NULL
            """)
    int transitionAssigneeTaskIfCurrent(@Param("taskId") Long taskId,
                                        @Param("assigneeId") Long assigneeId,
                                        @Param("expectedStatus") String expectedStatus,
                                        @Param("newStatus") String newStatus,
                                        @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            <script>
            UPDATE task
            SET assignee_id = #{newAssigneeId}, updated_at = #{updatedAt}
            WHERE id = #{taskId} AND status = 'TODO' AND deleted_at IS NULL
              AND (assignee_id = #{expectedAssigneeId}
                   OR (assignee_id IS NULL AND #{expectedAssigneeId} IS NULL))
            </script>
            """)
    int reassignTodoIfCurrent(@Param("taskId") Long taskId,
                              @Param("expectedAssigneeId") Long expectedAssigneeId,
                              @Param("newAssigneeId") Long newAssigneeId,
                              @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE task
            SET status = 'CANCELLED', cancel_reason = #{reason}, cancelled_at = #{cancelledAt},
                completed_at = NULL, updated_at = #{cancelledAt}
            WHERE id = #{taskId} AND status IN ('TODO', 'IN_PROGRESS') AND deleted_at IS NULL
            """)
    int cancelIfActive(@Param("taskId") Long taskId,
                       @Param("reason") String reason,
                       @Param("cancelledAt") LocalDateTime cancelledAt);
    @Select("""
        SELECT
            t.id AS id,
            t.project_id AS projectId,

            t.creator_id AS creatorId,
            creator.real_name AS creatorName,

            t.assignee_id AS assigneeId,
            assignee.real_name AS assigneeName,

            t.title AS title,
            t.description AS description,
            t.goal AS goal,

            t.status AS status,
            t.priority AS priority,

            t.deadline AS deadline,
            t.completed_at AS completedAt,
            t.created_at AS createdAt,
            t.updated_at AS updatedAt

        FROM task t

        JOIN `user` creator
            ON t.creator_id = creator.id

        LEFT JOIN `user` assignee
            ON t.assignee_id = assignee.id
        
        WHERE t.project_id = #{projectId}
            AND t.deleted_at IS NULL
            AND (
                #{scope} = 'ALL'
                OR (
                    #{scope} = 'MINE'
                    AND t.assignee_id = #{currentUserId}
            )
        )
        AND (
            #{status} = 'ALL'
            OR t.status = #{status}
        )
        
        ORDER BY t.created_at DESC, t.id DESC
        """)
    List<TaskVO> selectProjectTasks(
            @Param("projectId") Long projectId,
            @Param("scope") String scope,
            @Param("currentUserId") Long currentUserId,
            @Param("status") String status);

    @Select("""
        SELECT
            t.id AS id,
            t.project_id AS projectId,

            t.creator_id AS creatorId,
            creator.real_name AS creatorName,

            t.assignee_id AS assigneeId,
            assignee.real_name AS assigneeName,

            t.title AS title,
            t.description AS description,
            t.goal AS goal,

            t.status AS status,
            t.priority AS priority,

            t.deadline AS deadline,
            t.completed_at AS completedAt,
            t.created_at AS createdAt,
            t.updated_at AS updatedAt

        FROM task t

        JOIN `user` creator
            ON t.creator_id = creator.id

        LEFT JOIN `user` assignee
            ON t.assignee_id = assignee.id

        WHERE t.id = #{taskId}
          AND t.deleted_at IS NULL

        LIMIT 1
        """)
    TaskVO selectTaskDetail(@Param("taskId") Long taskId);

    @Select("""
        SELECT t.id, t.project_id AS projectId, t.creator_id AS creatorId,
               creator.real_name AS creatorName, t.assignee_id AS assigneeId,
               assignee.real_name AS assigneeName, t.title, t.description, t.goal,
               t.status, t.priority, t.deadline, t.completed_at AS completedAt,
               t.created_at AS createdAt, t.updated_at AS updatedAt
        FROM task t
        JOIN `user` creator ON t.creator_id = creator.id
        LEFT JOIN `user` assignee ON t.assignee_id = assignee.id
        JOIN project p ON p.id = t.project_id AND p.deleted_at IS NULL
        JOIN project_member pm
          ON pm.project_id = t.project_id AND pm.user_id = #{userId} AND pm.status = 'ACTIVE'
        JOIN `user` current_user
          ON current_user.id = #{userId}
         AND current_user.status = 'ACTIVE' AND current_user.system_role = 'USER'
        WHERE t.assignee_id = #{userId} AND t.deleted_at IS NULL
        ORDER BY t.updated_at DESC, t.id DESC
        """)
    List<TaskVO> selectMyTasks(@Param("userId") Long userId);
}
