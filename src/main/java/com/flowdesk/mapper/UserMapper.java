package com.flowdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

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