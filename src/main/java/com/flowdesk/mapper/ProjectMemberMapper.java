package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.vo.ProjectMemberVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
