package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.ConversationEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话事件审计 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface ConversationEventMapper extends BaseMapper<ConversationEvent> {

    @Select("SELECT * FROM conversation_event WHERE conversation_id = #{conversationId} ORDER BY create_time ASC")
    List<ConversationEvent> selectByConversationId(@Param("conversationId") String conversationId);
}
