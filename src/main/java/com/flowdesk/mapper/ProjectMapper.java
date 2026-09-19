package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.Project;
import com.flowdesk.vo.ProjectVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    @Select("""
        SELECT p.id, p.creator_id AS creatorId, p.name, p.description, p.goal, p.status,
               p.start_time AS startTime, p.expected_end_time AS expectedEndTime,
               p.actual_end_time AS actualEndTime, p.created_at AS createdAt, p.updated_at AS updatedAt
        FROM project p
        JOIN project_member pm ON pm.project_id = p.id
        WHERE pm.user_id = #{userId}
          AND pm.status = 'ACTIVE'
          AND p.deleted_at IS NULL
        ORDER BY p.updated_at DESC, p.id DESC
        """)
    List<ProjectVO> selectProjectsForUser(@Param("userId") Long userId);
}
