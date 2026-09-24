package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.ProjectManagerTransfer;
import com.flowdesk.vo.ProjectManagerTransferVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ProjectManagerTransferMapper
        extends BaseMapper<ProjectManagerTransfer> {

    @Update("""
        UPDATE project_manager_transfer
        SET status = 'ACCEPTED',
            responded_at = #{respondedAt}
        WHERE id = #{transferId}
          AND project_id = #{projectId}
          AND to_user_id = #{toUserId}
          AND status = 'PENDING'
          AND expires_at > #{now}
        """)
    int acceptIfPending(
            @Param("transferId") Long transferId,
            @Param("projectId") Long projectId,
            @Param("toUserId") Long toUserId,
            @Param("respondedAt") LocalDateTime respondedAt,
            @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE project_manager_transfer
        SET status = 'REJECTED',
            responded_at = #{respondedAt}
        WHERE id = #{transferId}
          AND project_id = #{projectId}
          AND to_user_id = #{toUserId}
          AND status = 'PENDING'
          AND expires_at > #{now}
        """)
    int rejectIfPending(
            @Param("transferId") Long transferId,
            @Param("projectId") Long projectId,
            @Param("toUserId") Long toUserId,
            @Param("respondedAt") LocalDateTime respondedAt,
            @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE project_manager_transfer
        SET status = 'CANCELLED',
            cancelled_at = #{cancelledAt}
        WHERE id = #{transferId}
          AND project_id = #{projectId}
          AND from_user_id = #{fromUserId}
          AND status = 'PENDING'
          AND expires_at > #{now}
        """)
    int cancelIfPending(
            @Param("transferId") Long transferId,
            @Param("projectId") Long projectId,
            @Param("fromUserId") Long fromUserId,
            @Param("cancelledAt") LocalDateTime cancelledAt,
            @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT
            pmt.id AS id,
            pmt.project_id AS projectId,
            p.name AS projectName,

            pmt.from_user_id AS fromUserId,
            from_user.real_name AS fromUserName,

            pmt.to_user_id AS toUserId,
            to_user.real_name AS toUserName,

            pmt.old_manager_action AS oldManagerAction,
            pmt.status AS status,

            pmt.created_at AS createdAt,
            pmt.expires_at AS expiresAt,
            pmt.responded_at AS respondedAt,
            pmt.cancelled_at AS cancelledAt

        FROM project_manager_transfer pmt

        JOIN project p
            ON p.id = pmt.project_id

        JOIN `user` from_user
            ON from_user.id = pmt.from_user_id

        JOIN `user` to_user
            ON to_user.id = pmt.to_user_id

        WHERE pmt.id = #{transferId}
          AND pmt.project_id = #{projectId}

        LIMIT 1
        """)
    ProjectManagerTransferVO selectDetail(
            @Param("projectId") Long projectId,
            @Param("transferId") Long transferId
    );
}