package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("""
        SELECT u.id AS userId, u.username, u.real_name AS realName, u.status AS userStatus,
          CASE
            WHEN EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = #{projectId}
                         AND pm.user_id = u.id AND pm.status = 'ACTIVE') THEN 'JOINED'
            WHEN u.status != 'ACTIVE' THEN 'DISABLED'
            WHEN EXISTS (SELECT 1 FROM project_invitation pi WHERE pi.project_id = #{projectId}
                         AND pi.invitee_id = u.id AND pi.status = 'PENDING'
                         AND pi.expires_at > #{now}) THEN 'PENDING'
            WHEN EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = #{projectId}
                         AND pm.user_id = u.id AND pm.status = 'INACTIVE') THEN 'REINVITE'
            ELSE 'INVITE'
          END AS candidateStatus
        FROM `user` u
        WHERE u.system_role = 'USER'
        ORDER BY u.username, u.id
        """)
    java.util.List<com.flowdesk.vo.MemberCandidateVO> selectMemberCandidates(
            @Param("projectId") Long projectId, @Param("now") java.time.LocalDateTime now);

    @Select("""
            SELECT *
            FROM `user`
            WHERE id = #{userId}
            FOR UPDATE
            """)
    User selectByIdForUpdate(
            @Param("userId") Long userId
    );
}