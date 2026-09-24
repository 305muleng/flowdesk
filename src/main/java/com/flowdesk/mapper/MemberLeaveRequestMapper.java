package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.MemberLeaveRequest;
import com.flowdesk.vo.MemberLeaveRequestVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MemberLeaveRequestMapper
        extends BaseMapper<MemberLeaveRequest> {

    @Update("""
            UPDATE member_leave_request
            SET status = 'CANCELLED',
                cancelled_at = #{cancelledAt}
            WHERE id = #{requestId}
              AND project_id = #{projectId}
              AND applicant_id = #{applicantId}
              AND status = 'PENDING'
              AND expires_at > #{now}
            """)
    int cancelIfPending(
            @Param("requestId") Long requestId,
            @Param("projectId") Long projectId,
            @Param("applicantId") Long applicantId,
            @Param("cancelledAt") LocalDateTime cancelledAt,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE member_leave_request
            SET status = #{newStatus},
                reviewer_id = #{reviewerId},
                review_note = #{reviewNote},
                reviewed_at = #{reviewedAt}
            WHERE id = #{requestId}
              AND project_id = #{projectId}
              AND status = 'PENDING'
              AND expires_at > #{now}
            """)
    int reviewIfPending(
            @Param("requestId") Long requestId,
            @Param("projectId") Long projectId,
            @Param("newStatus") String newStatus,
            @Param("reviewerId") Long reviewerId,
            @Param("reviewNote") String reviewNote,
            @Param("reviewedAt") LocalDateTime reviewedAt,
            @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT
            mlr.id AS id,
            mlr.project_id AS projectId,

            mlr.applicant_id AS applicantId,
            applicant.real_name AS applicantName,

            mlr.reason AS reason,
            mlr.status AS status,

            mlr.reviewer_id AS reviewerId,
            reviewer.real_name AS reviewerName,
            mlr.review_note AS reviewNote,

            mlr.created_at AS createdAt,
            mlr.expires_at AS expiresAt,
            mlr.reviewed_at AS reviewedAt,
            mlr.cancelled_at AS cancelledAt

        FROM member_leave_request mlr

        JOIN `user` applicant
            ON mlr.applicant_id = applicant.id

        LEFT JOIN `user` reviewer
            ON mlr.reviewer_id = reviewer.id

        WHERE mlr.id = #{requestId}
          AND mlr.project_id = #{projectId}

        LIMIT 1
        """)
    MemberLeaveRequestVO selectDetail(
            @Param("projectId") Long projectId,
            @Param("requestId") Long requestId);

    @Update("""
        UPDATE member_leave_request
        SET status = 'CANCELLED',
            cancelled_at = #{cancelledAt}
        WHERE project_id = #{projectId}
          AND applicant_id = #{applicantId}
          AND status = 'PENDING'
        """)
    int cancelPendingByApplicant(
            @Param("projectId") Long projectId,
            @Param("applicantId") Long applicantId,
            @Param("cancelledAt") LocalDateTime cancelledAt);
}