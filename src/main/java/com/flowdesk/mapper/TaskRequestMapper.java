package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.TaskRequest;
import com.flowdesk.vo.TaskRequestVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskRequestMapper extends BaseMapper<TaskRequest> {
    @Select("""
        SELECT
            tr.id AS id,
            tr.project_id AS projectId,

            tr.requester_id AS requesterId,
            requester.real_name AS requesterName,

            tr.title AS title,
            tr.description AS description,
            tr.goal AS goal,
            tr.suggested_deadline AS suggestedDeadline,

            tr.status AS status,

            tr.reviewer_id AS reviewerId,
            reviewer.real_name AS reviewerName,
            tr.review_note AS reviewNote,

            tr.task_id AS taskId,
            tr.created_at AS createdAt,
            tr.reviewed_at AS reviewedAt,
            tr.cancelled_at AS cancelledAt

        FROM task_request tr

        JOIN `user` requester
            ON tr.requester_id = requester.id

        LEFT JOIN `user` reviewer
            ON tr.reviewer_id = reviewer.id

        WHERE tr.project_id = #{projectId}
          AND (
                #{status} = 'ALL'
                OR tr.status = #{status}
          )

        ORDER BY tr.created_at DESC
        """)
    List<TaskRequestVO> selectProjectTaskRequests(@Param("projectId") Long projectId, @Param("status") String status);

    @Select("""
        SELECT tr.id, tr.project_id AS projectId,
               tr.requester_id AS requesterId, requester.real_name AS requesterName,
               tr.title, tr.description, tr.goal,
               tr.suggested_deadline AS suggestedDeadline, tr.status,
               tr.reviewer_id AS reviewerId, reviewer.real_name AS reviewerName,
               tr.review_note AS reviewNote, tr.task_id AS taskId,
               tr.created_at AS createdAt, tr.reviewed_at AS reviewedAt,
               tr.cancelled_at AS cancelledAt
        FROM task_request tr
        JOIN `user` requester ON tr.requester_id = requester.id
        LEFT JOIN `user` reviewer ON tr.reviewer_id = reviewer.id
        WHERE tr.requester_id = #{userId}
          AND (#{status} = 'ALL' OR tr.status = #{status})
        ORDER BY tr.created_at DESC
        """)
    List<TaskRequestVO> selectMyTaskRequests(@Param("userId") Long userId, @Param("status") String status);
}
