package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.vo.ProjectMemberVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {
    @Select("""
        SELECT
            pm.user_id AS userId,
            u.username AS username,
            u.real_name AS realName,
            pm.role AS role,
            pm.joined_at AS joinedAt
        FROM project_member pm
        JOIN `user` u
            ON pm.user_id = u.id
        WHERE pm.project_id = #{projectId}
          AND pm.status = 'ACTIVE'
          AND u.status = 'ACTIVE'
          AND u.system_role = 'USER'
        ORDER BY pm.joined_at ASC
        """)
    List<ProjectMemberVO> selectActiveMembers(@Param("projectId") Long projectId);

    @Select("""
        SELECT
            pm.user_id AS userId,
            u.username AS username,
            u.real_name AS realName,
            pm.role AS role,
            pm.joined_at AS joinedAt
        FROM project_member pm
        JOIN `user` u
            ON pm.user_id = u.id
        WHERE pm.project_id = #{projectId}
          AND pm.role = 'PROJECT_MANAGER'
          AND pm.status = 'ACTIVE'
          AND u.status = 'ACTIVE'
          AND u.system_role = 'USER'
        LIMIT 1
        """)
    ProjectMemberVO selectActiveProjectManager(
            @Param("projectId") Long projectId);

    @Update("""
        UPDATE project_member
        SET status = 'INACTIVE',
            left_at = #{leftAt},
            updated_at = #{leftAt}
        WHERE project_id = #{projectId}
          AND user_id = #{userId}
          AND role = 'DEVELOPER'
          AND status = 'ACTIVE'
        """)
    int deactivateDeveloperIfActive(
            @Param("projectId") Long projectId,
            @Param("userId") Long userId,
            @Param("leftAt") LocalDateTime leftAt);

    @Update("""
        UPDATE project_member
        SET role = 'DEVELOPER',
            status = 'ACTIVE',
            joined_at = #{joinedAt},
            left_at = NULL,
            updated_at = #{joinedAt}
        WHERE id = #{memberId}
          AND status = 'INACTIVE'
        """)
    int reactivateAsDeveloper(
            @Param("memberId") Long memberId,
            @Param("joinedAt") LocalDateTime joinedAt);

    @Update("""
        UPDATE project_member
        SET role = 'PROJECT_MANAGER',
            updated_at = #{updatedAt}
        WHERE project_id = #{projectId}
          AND user_id = #{userId}
          AND role = 'DEVELOPER'
          AND status = 'ACTIVE'
        """)
    int promoteDeveloperToManager(
            @Param("projectId") Long projectId,
            @Param("userId") Long userId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
        UPDATE project_member
        SET role = 'PROJECT_MANAGER',
            status = 'ACTIVE',
            joined_at = #{joinedAt},
            left_at = NULL,
            updated_at = #{joinedAt}
        WHERE id = #{memberId}
          AND status = 'INACTIVE'
        """)
    int reactivateAsManager(
            @Param("memberId") Long memberId,
            @Param("joinedAt") LocalDateTime joinedAt
    );

    @Update("""
        UPDATE project_member
        SET role = 'DEVELOPER',
            status = 'ACTIVE',
            left_at = NULL,
            updated_at = #{updatedAt}
        WHERE project_id = #{projectId}
          AND user_id = #{userId}
          AND role = 'PROJECT_MANAGER'
          AND status = 'ACTIVE'
        """)
    int demoteManagerToDeveloper(
            @Param("projectId") Long projectId,
            @Param("userId") Long userId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
        UPDATE project_member
        SET status = 'INACTIVE',
            left_at = #{leftAt},
            updated_at = #{leftAt}
        WHERE project_id = #{projectId}
          AND user_id = #{userId}
          AND role = 'PROJECT_MANAGER'
          AND status = 'ACTIVE'
        """)
    int deactivateManagerIfActive(
            @Param("projectId") Long projectId,
            @Param("userId") Long userId,
            @Param("leftAt") LocalDateTime leftAt
    );

    @Select("""
        SELECT COUNT(*)
        FROM project_member pm
        JOIN project p
          ON p.id = pm.project_id
        WHERE pm.user_id = #{userId}
          AND pm.role = 'PROJECT_MANAGER'
          AND pm.status = 'ACTIVE'
          AND p.status IN (
              'PREPARING',
              'IN_PROGRESS',
              'PENDING_ACCEPTANCE',
              'COMPLETED'
          )
          AND p.deleted_at IS NULL
        """)
    Long countActiveManagedProjects(
            @Param("userId") Long userId
    );
}
