package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 对话消息 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT * FROM chat_message WHERE conversation_id = #{conversationId} ORDER BY create_time ASC")
    List<ChatMessage> selectByConversationId(@Param("conversationId") String conversationId);

    /**
     * 查最近 N 条消息（按时间正序），用于 ChatMemory 回填。
     * 先按时间倒序取 N 条，再在 Java 层反转为正序。
     */
    @Select("SELECT * FROM chat_message WHERE conversation_id = #{conversationId} ORDER BY create_time DESC LIMIT #{limit}")
    List<ChatMessage> selectRecentByConversationId(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM chat_message WHERE conversation_id = #{conversationId}")
    int countByConversationId(@Param("conversationId") String conversationId);
}
