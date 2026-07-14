package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话消息实体
 * <p>记录每条对话消息：用户提问、AI答案+CoT、工具调用、系统分界线、人工回复（预留）。
 * 支持会话切换时从该表回填最近10条到 Redis ChatMemory。</p>
 *
 * @author Joseph
 */
@TableName(value = "chat_message")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;

    private String conversationId;

    private Long userId;

    /** USER / ASSISTANT / SYSTEM / TOOL / HUMAN_AGENT */
    private String role;

    private String content;

    /** TEXT / MARKDOWN / IMAGE / TOOL_CALL / STEP */
    private String contentType;

    /** 意图分类（仅 ASSISTANT） */
    private String category;

    /** CoT 思考过程（仅 ASSISTANT） */
    private String cotContent;

    /** 工具名称（仅 TOOL） */
    private String toolName;

    /** 关联 mcp_call_log 的调用 ID（仅 TOOL） */
    private String toolCallId;

    /** 检索来源文档列表 JSON 字符串（仅 ASSISTANT） */
    private String retrievalSources;

    private Integer tokensInput;

    private Integer tokensOutput;

    private Long durationMs;

    /** 是否触发转人工（0/1） */
    private Integer isEscalated;

    private Date createTime;
}
