package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.ProjectInvitation;
import com.flowdesk.vo.InvitationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ProjectInvitationMapper
        extends BaseMapper<ProjectInvitation> {

    @Select("""
            SELECT
                pi.id AS id,
                pi.project_id AS projectId,
                p.name AS projectName,
                pi.inviter_id AS inviterId,
                u.real_name AS inviterName,
                pi.status AS status,
                pi.created_at AS createdAt,
                pi.expires_at AS expiresAt,
                pi.responded_at AS respondedAt
            FROM project_invitation pi
            JOIN project p
                ON pi.project_id = p.id
            JOIN `user` u
                ON pi.inviter_id = u.id
            WHERE pi.invitee_id = #{inviteeId}
              AND (
                    #{filter} = 'ALL'
            
                    OR (
                        #{filter} = 'PENDING'
                        AND pi.status = 'PENDING'
                        AND pi.expires_at > #{now}
                    )
            
                    OR (
                        #{filter} = 'ACCEPTED'
                        AND pi.status = 'ACCEPTED'
                    )
            
                    OR (
                        #{filter} = 'REJECTED'
                        AND pi.status = 'REJECTED'
                    )
            
                    OR (
                        #{filter} = 'EXPIRED'
                        AND pi.status = 'PENDING'
                        AND pi.expires_at <= #{now}
                    )
              )
            ORDER BY pi.created_at DESC
            """)
    List<InvitationVO> selectMyInvitations(
            @Param("inviteeId") Long inviteeId,
            @Param("filter") String filter,
            @Param("now") LocalDateTime now);
}