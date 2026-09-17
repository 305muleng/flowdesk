package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.TaskComment;
import com.flowdesk.vo.TaskCommentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskCommentMapper extends BaseMapper<TaskComment> {

    @Select("""
            SELECT
                tc.id AS id,
                tc.parent_id AS parentId,
                tc.author_id AS authorId,
                u.real_name AS authorName,
                tc.content AS content,
                tc.created_at AS createdAt,
                tc.edited_at AS editedAt
            FROM task_comment tc
            JOIN `user` u
                ON tc.author_id = u.id
            WHERE tc.task_id = #{taskId}
              AND tc.deleted_at IS NULL
            ORDER BY tc.created_at ASC
            """)
    List<TaskCommentVO> selectTaskComments(@Param("taskId") Long taskId);
}