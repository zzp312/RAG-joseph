package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 对话会话 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Select("SELECT * FROM conversation WHERE conversation_id = #{conversationId} LIMIT 1")
    Conversation selectByConversationId(@Param("conversationId") String conversationId);

    @Select("SELECT * FROM conversation WHERE user_id = #{userId} AND status != 'ARCHIVED' ORDER BY update_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<Conversation> selectByUserId(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM conversation WHERE user_id = #{userId} AND status != 'ARCHIVED'")
    int countByUserId(@Param("userId") Long userId);

    @Update("UPDATE conversation SET first_message = IF(first_message IS NULL, #{firstMessage}, first_message), title = IF(title IS NULL, #{title}, title), last_message = #{lastMessage}, message_count = message_count + 1, update_time = NOW(3) WHERE conversation_id = #{conversationId}")
    int updateLastMessage(@Param("conversationId") String conversationId, @Param("lastMessage") String lastMessage, @Param("firstMessage") String firstMessage, @Param("title") String title);

    @Update("UPDATE conversation SET status = #{status}, escalate_reason = #{reason}, update_time = NOW(3) WHERE conversation_id = #{conversationId}")
    int updateStatus(@Param("conversationId") String conversationId, @Param("status") String status, @Param("reason") String reason);

    @Update("UPDATE conversation SET status = 'ARCHIVED', update_time = NOW(3) WHERE conversation_id = #{conversationId}")
    int archiveByConversationId(@Param("conversationId") String conversationId);
}
