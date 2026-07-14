package com.xushu.rag.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 会话详情 DTO（含全量消息列表）
 *
 * @author Joseph
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationDetailDTO {
    private String conversationId;
    private String title;
    private String status;
    private String kbIds;
    private Date createTime;
    private Date updateTime;
    private Integer messageCount;
    private List<ChatMessageDTO> messages;
}
