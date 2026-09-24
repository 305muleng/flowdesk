package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.TaskRequest;
import com.flowdesk.vo.TaskRequestVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

import java.util.List;

@Mapper
public interface TaskRequestMapper extends BaseMapper<TaskRequest> {
    @Update("""
            UPDATE task_request
            SET status = #{newStatus}, reviewer_id = #{reviewerId},
                review_note = #{reviewNote}, reviewed_at = #{reviewedAt}
            WHERE id = #{requestId} AND project_id = #{projectId} AND status = 'PENDING'
            """)
    int reviewIfPending(@Param("requestId") Long requestId,
                        @Param("projectId") Long projectId,
                        @Param("newStatus") String newStatus,
                        @Param("reviewerId") Long reviewerId,
                        @Param("reviewNote") String reviewNote,
                        @Param("reviewedAt") LocalDateTime reviewedAt);

    @Update("""
            UPDATE task_request
            SET status = 'CANCELLED', cancelled_at = #{cancelledAt}
            WHERE id = #{requestId} AND project_id = #{projectId}
              AND requester_id = #{requesterId} AND status = 'PENDING'
            """)
    int cancelIfPending(@Param("requestId") Long requestId,
                        @Param("projectId") Long projectId,
                        @Param("requesterId") Long requesterId,
                        @Param("cancelledAt") LocalDateTime cancelledAt);
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

        ORDER BY tr.created_at DESC, tr.id DESC
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
        ORDER BY tr.created_at DESC, tr.id DESC
        """)
    List<TaskRequestVO> selectMyTaskRequests(@Param("userId") Long userId, @Param("status") String status);

    @Update("""
        UPDATE task_request
        SET status = 'CANCELLED',
            cancelled_at = #{cancelledAt}
        WHERE project_id = #{projectId}
          AND requester_id = #{requesterId}
          AND status = 'PENDING'
        """)
    int cancelPendingByRequester(
            @Param("projectId") Long projectId,
            @Param("requesterId") Long requesterId,
            @Param("cancelledAt") LocalDateTime cancelledAt);
}
