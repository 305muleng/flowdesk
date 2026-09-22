package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.Project;
import com.flowdesk.vo.ProjectVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    @Update("""
            UPDATE project
            SET status = 'IN_PROGRESS', start_time = #{startedAt}, updated_at = #{startedAt}
            WHERE id = #{projectId} AND status = 'PREPARING' AND deleted_at IS NULL
            """)
    int startIfPreparing(@Param("projectId") Long projectId,
                         @Param("startedAt") LocalDateTime startedAt);

    @Update("""
            UPDATE project
            SET status = 'CANCELLED', cancel_reason = #{reason}, cancelled_at = #{cancelledAt},
                actual_end_time = NULL, updated_at = #{cancelledAt}
            WHERE id = #{projectId} AND status IN ('PREPARING', 'IN_PROGRESS')
              AND deleted_at IS NULL
            """)
    int cancelIfActive(@Param("projectId") Long projectId,
                       @Param("reason") String reason,
                       @Param("cancelledAt") LocalDateTime cancelledAt);

    @Update("""
            UPDATE project
            SET status = 'ARCHIVED', updated_at = #{archivedAt}
            WHERE id = #{projectId} AND status = 'COMPLETED' AND deleted_at IS NULL
            """)
    int archiveIfCompleted(@Param("projectId") Long projectId,
                           @Param("archivedAt") LocalDateTime archivedAt);

    @Update("""
            UPDATE project
            SET status = 'PENDING_ACCEPTANCE', updated_at = #{submittedAt}
            WHERE id = #{projectId} AND status = 'IN_PROGRESS' AND deleted_at IS NULL
            """)
    int submitAcceptanceIfInProgress(@Param("projectId") Long projectId,
                                     @Param("submittedAt") LocalDateTime submittedAt);

    @Update("""
            UPDATE project
            SET status = #{newStatus}, actual_end_time = #{actualEndTime}, updated_at = #{reviewedAt}
            WHERE id = #{projectId} AND status = 'PENDING_ACCEPTANCE' AND deleted_at IS NULL
            """)
    int reviewAcceptanceIfPending(@Param("projectId") Long projectId,
                                  @Param("newStatus") String newStatus,
                                  @Param("actualEndTime") LocalDateTime actualEndTime,
                                  @Param("reviewedAt") LocalDateTime reviewedAt);

    @Select("SELECT * FROM project WHERE id = #{projectId} FOR UPDATE")
    Project selectByIdForUpdate(@Param("projectId") Long projectId);

    @Select("""
        SELECT p.id, p.creator_id AS creatorId, p.name, p.description, p.goal, p.status,
               p.start_time AS startTime, p.expected_end_time AS expectedEndTime,
               p.actual_end_time AS actualEndTime, p.created_at AS createdAt, p.updated_at AS updatedAt
        FROM project p
        JOIN project_member pm ON pm.project_id = p.id
        JOIN `user` u ON u.id = pm.user_id
        WHERE pm.user_id = #{userId}
          AND pm.status = 'ACTIVE'
          AND u.status = 'ACTIVE'
          AND u.system_role = 'USER'
          AND p.deleted_at IS NULL
        ORDER BY p.updated_at DESC, p.id DESC
        """)
    List<ProjectVO> selectProjectsForUser(@Param("userId") Long userId);
}
