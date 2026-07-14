package com.xushu.rag.conversation;

import com.xushu.rag.entity.Conversation;
import com.xushu.rag.entity.ConversationEvent;
import com.xushu.rag.entity.ChatMessage;
import com.xushu.rag.mapper.ChatMessageMapper;
import com.xushu.rag.mapper.ConversationEventMapper;
import com.xushu.rag.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 对话持久化服务
 * <p>统一管理对话写入：用户消息(同步)、AI答案+CoT(异步)、工具调用(异步)、
 * 系统消息(异步)、懒创建会话、状态更新、归档、事件记录。</p>
 *
 * @author Joseph
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationPersistenceService {

    private static final int LAST_MESSAGE_MAX_LENGTH = 200;
    private static final int TITLE_MAX_LENGTH = 50;

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationEventMapper conversationEventMapper;

    /**
     * 懒创建会话：不存在时创建，存在则返回。
     */
    public Conversation createConversationIfAbsent(String conversationId, Long userId, String kbIds) {
        Conversation existing = conversationMapper.selectByConversationId(conversationId);
        if (existing != null) {
            return existing;
        }

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .userId(userId)
                .kbIds(kbIds)
                .status("ACTIVE")
                .messageCount(0)
                .tokenTotal(0)
                .build();
        conversationMapper.insert(conversation);
        log.info("[会话创建] conversationId={}, userId={}, kbIds={}", conversationId, userId, kbIds);
        return conversation;
    }

    /**
     * 同步写入用户消息 + 更新 conversation.last_message
     */
    public void saveUserMessage(String conversationId, Long userId, String content) {
        ChatMessage message = ChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .userId(userId)
                .role("USER")
                .content(content)
                .contentType("TEXT")
                .isEscalated(0)
                .build();
        chatMessageMapper.insert(message);
        String truncated = truncate(content);
        conversationMapper.updateLastMessage(conversationId, truncated, truncated, truncate(content, TITLE_MAX_LENGTH));
    }

    /**
     * 异步写入 AI 答案（含 CoT）
     */
    @Async
    public void saveAssistantMessage(String conversationId, Long userId, String answer,
                                     String cot, String category, String sources,
                                     Integer tokensInput, Integer tokensOutput, Long durationMs) {
        try {
            ChatMessage message = ChatMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .conversationId(conversationId)
                    .userId(userId)
                    .role("ASSISTANT")
                    .content(answer)
                    .contentType("MARKDOWN")
                    .category(category)
                    .cotContent(cot)
                    .retrievalSources(sources)
                    .tokensInput(tokensInput)
                    .tokensOutput(tokensOutput)
                    .durationMs(durationMs)
                    .isEscalated(0)
                    .build();
            chatMessageMapper.insert(message);
            conversationMapper.updateLastMessage(conversationId, truncate(answer), null, null);
        } catch (Exception e) {
            log.warn("[AI消息写入失败] conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 异步写入工具调用
     */
    @Async
    public void saveToolMessage(String conversationId, Long userId, String toolName,
                                String result, String toolCallId, String cotContent) {
        try {
            ChatMessage message = ChatMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .conversationId(conversationId)
                    .userId(userId)
                    .role("TOOL")
                    .content(result)
                    .contentType("TOOL_CALL")
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .cotContent(cotContent)
                    .isEscalated(0)
                    .build();
            chatMessageMapper.insert(message);
        } catch (Exception e) {
            log.warn("[工具消息写入失败] conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 异步写入系统消息（转人工/回AI 分界线）
     */
    @Async
    public void saveSystemMessage(String conversationId, Long userId, String content, String type) {
        try {
            ChatMessage message = ChatMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .conversationId(conversationId)
                    .userId(userId)
                    .role("SYSTEM")
                    .content(content)
                    .contentType("TEXT")
                    .isEscalated(0)
                    .build();
            chatMessageMapper.insert(message);
        } catch (Exception e) {
            log.warn("[系统消息写入失败] conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 更新会话状态
     */
    public void updateConversationStatus(String conversationId, String status, String reason) {
        conversationMapper.updateStatus(conversationId, status, reason);
    }

    /**
     * 软删除（status → ARCHIVED）
     */
    public void archiveConversation(String conversationId) {
        conversationMapper.archiveByConversationId(conversationId);
    }

    /**
     * 异步写入会话事件
     */
    @Async
    public void saveEvent(String conversationId, String eventType, String data,
                          Long operatorId, String operatorType) {
        try {
            ConversationEvent event = ConversationEvent.builder()
                    .conversationId(conversationId)
                    .eventType(eventType)
                    .eventData(data)
                    .operatorId(operatorId)
                    .operatorType(operatorType)
                    .build();
            conversationEventMapper.insert(event);
        } catch (Exception e) {
            log.warn("[事件写入失败] conversationId={}, eventType={}, error={}",
                    conversationId, eventType, e.getMessage());
        }
    }

    /**
     * 预留：人工坐席消息写入（当前不实现逻辑，只留方法签名）
     */
    @Async
    public void saveHumanAgentMessage(String conversationId, Long agentId, String content) {
        // 预留入口，Phase 2 实现坐席接手时再补充
        log.debug("[预留] 人工坐席消息写入, conversationId={}, agentId={}", conversationId, agentId);
    }

    private String truncate(String content) {
        return truncate(content, LAST_MESSAGE_MAX_LENGTH);
    }

    private String truncate(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength
                ? content
                : content.substring(0, maxLength);
    }
}
