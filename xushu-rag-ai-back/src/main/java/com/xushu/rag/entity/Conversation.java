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
 * 对话会话实体
 * <p>记录一次完整对话的元信息，支持多会话切换、状态流转、软删除归档。
 * 会话无自动结束机制，用户可从历史恢复任意会话继续提问。</p>
 *
 * @author Joseph
 */
@TableName(value = "conversation")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    private Long userId;

    private String title;

    private String kbIds;

    /** ACTIVE / ESCALATED / HUMAN_SERVING / ARCHIVED */
    private String status;

    /** emotion_negative / manual / auto_high_risk */
    private String escalateReason;

    private Long humanAgentId;

    private Integer messageCount;

    private String firstMessage;

    private String lastMessage;

    private Integer tokenTotal;

    private Date createTime;

    private Date updateTime;

    /** 扩展元数据 JSON 字符串（浏览器/IP/渠道等） */
    private String metadata;
}
