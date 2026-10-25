package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.model.OperationLog;
import com.flowdesk.vo.OperationLogVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
    @Select("""
        SELECT ol.id, ol.project_id AS projectId, ol.actor_id AS actorId,
               actor.real_name AS actorName, ol.target_type AS targetType,
               ol.target_id AS targetId, ol.action, ol.description,
               ol.before_data AS beforeData, ol.after_data AS afterData,
               ol.created_at AS createdAt
        FROM operation_log ol
        JOIN `user` actor ON ol.actor_id = actor.id
        WHERE ol.project_id = #{projectId}
        ORDER BY ol.created_at DESC, ol.id DESC
        """)
    IPage<OperationLogVO> selectProjectLogs(Page<OperationLogVO> page, @Param("projectId") Long projectId);
}
