package com.xushu.rag.conversation;

import com.xushu.rag.entity.ChatMessage;
import com.xushu.rag.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ChatMemory 回填服务
 * <p>切换历史会话时，如果 Redis ChatMemory 无该会话数据，
 * 从 MySQL chat_message 表回填最近 10 条 USER/ASSISTANT 消息。</p>
 *
 * @author Joseph
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryRestoreService {

    private static final int RESTORE_LIMIT = 10;

    private final ChatMemory chatMemory;
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 如果 Redis ChatMemory 中无该会话数据，从 MySQL 回填最近 10 条。
     * <p>只回填 USER 和 ASSISTANT 消息，跳过 TOOL/SYSTEM/HUMAN_AGENT。</p>
     *
     * @param conversationId 会话ID
     */
    public void restoreMemoryIfNeeded(String conversationId) {
        List<Message> existing = chatMemory.get(conversationId);
        if (existing != null && !existing.isEmpty()) {
            log.debug("[记忆回填] 跳过，Redis已有数据, conversationId={}", conversationId);
            return;
        }

        List<ChatMessage> dbMessages = chatMessageMapper.selectRecentByConversationId(conversationId, RESTORE_LIMIT);
        if (dbMessages == null || dbMessages.isEmpty()) {
            log.debug("[记忆回填] 跳过，MySQL无历史消息, conversationId={}", conversationId);
            return;
        }

        // MySQL 返回的是倒序（最新在前），反转为正序（最早在前）
        Collections.reverse(dbMessages);

        List<Message> toRestore = new ArrayList<>();
        for (ChatMessage m : dbMessages) {
            if (m.getContent() == null || m.getContent().isEmpty()) {
                continue;
            }
            if ("USER".equals(m.getRole())) {
                toRestore.add(new UserMessage(m.getContent()));
            } else if ("ASSISTANT".equals(m.getRole())) {
                toRestore.add(new AssistantMessage(m.getContent()));
            }
        }

        if (toRestore.isEmpty()) {
            log.debug("[记忆回填] 跳过，无USER/ASSISTANT消息, conversationId={}", conversationId);
            return;
        }

        chatMemory.clear(conversationId);
        chatMemory.add(conversationId, toRestore);
        log.info("[记忆回填] 成功, conversationId={}, 回填{}条消息", conversationId, toRestore.size());
    }
}
