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
 * 会话事件审计实体
 * <p>记录会话生命周期中的关键事件：创建/AI回答/转人工/坐席接入/坐席回复/回AI/归档。
 * 用于审计追溯和客服工作台动态展示。</p>
 *
 * @author Joseph
 */
@TableName(value = "conversation_event")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** CREATED / AI_RESPONDED / ESCALATED / HUMAN_JOINED / HUMAN_REPLIED / BACK_TO_AI / ARCHIVED */
    private String eventType;

    /** 事件详情 JSON 字符串 */
    private String eventData;

    private Long operatorId;

    /** USER / HUMAN_AGENT / SYSTEM */
    private String operatorType;

    private Date createTime;
}
