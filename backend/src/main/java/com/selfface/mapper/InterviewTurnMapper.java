package com.selfface.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.selfface.entity.InterviewTurn;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface InterviewTurnMapper extends BaseMapper<InterviewTurn> {

    /** 一次会话的全部对话，按先后顺序 */
    @Select("""
            SELECT * FROM interview_turn
            WHERE session_id = #{sessionId}
            ORDER BY seq ASC, id ASC
            """)
    List<InterviewTurn> listBySession(@Param("sessionId") Long sessionId);

    /**
     * 当前最大序号。新增轮次时用它 +1。
     * 不能用 COUNT(*)：中途若删过轮次，COUNT 会和真实序号错位。
     */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM interview_turn WHERE session_id = #{sessionId}")
    int maxSeq(@Param("sessionId") Long sessionId);

    /**
     * 删除会话时一并清掉对话轮次。
     * 表之间没有建外键，级联只能靠这里保证——否则删了会话会留下永远读不到的轮次记录。
     */
    @Delete("DELETE FROM interview_turn WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") Long sessionId);
}
