package com.xushu.rag.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话列表项 DTO（列表展示用）
 *
 * @author Joseph
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationListItemDTO {
    private String conversationId;
    private String title;
    private String firstMessage;
    private String lastMessage;
    private String status;
    private Date updateTime;
    private Integer messageCount;
}
