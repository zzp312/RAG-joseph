package com.xushu.rag.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话消息 DTO（详情展示用）
 *
 * @author Joseph
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageDTO {
    private Long id;
    private String role;
    private String content;
    private String cotContent;
    private String category;
    private String toolName;
    private String retrievalSources;
    private Date createTime;
}
