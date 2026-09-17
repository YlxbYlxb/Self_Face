package com.selfface.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.selfface.entity.InterviewSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface InterviewSessionMapper extends BaseMapper<InterviewSession> {

    /**
     * 某用户的面试历史，最近的在前面。
     */
    @Select("""
            SELECT * FROM interview_session
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<InterviewSession> listByUser(@Param("userId") Long userId, @Param("limit") int limit);
}
