package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.Task;
import com.flowdesk.vo.TaskVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
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
        
        ORDER BY t.created_at DESC 
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
}