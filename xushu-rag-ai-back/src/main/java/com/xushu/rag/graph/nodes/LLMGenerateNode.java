package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM生成Node
 * <p>调用ChatClient生成最终答案，使用提示词模板 + RAG上下文</p>
 * <p>内置对话记忆（PromptChatMemoryAdvisor），保留最近5轮对话历史，
 * 切换知识库/文件时由GraphChatController通过fingerprint检测自动清除</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class LLMGenerateNode {

    /** 对话记忆保留消息数（5轮 = 10条消息） */
    private static final int CHAT_MEMORY_RESPONSE_SIZE = 10;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public LLMGenerateNode(ChatModel chatModel, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(chatMemory)
                                .build()
                )
                .build();
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        String systemPrompt = (String) state.getOrDefault(StateKeys.SYSTEM_PROMPT, "请回答用户问题。");
        String context = (String) state.getOrDefault(StateKeys.CONTEXT, "");
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
        String conversationId = (String) state.getOrDefault(StateKeys.CONVERSATION_ID, "default");

        // 系统提示词中不再注入文档上下文，替换 {context} 为空
        // 文档上下文改为注入到用户消息中（对标 MetadataAwareQuestionAnswerAdvisor 格式），
        // 提升LLM对检索结果的关注度，避免系统提示词过长导致LLM忽略上下文
        String finalSystem = systemPrompt.replace("{context}", "");

        // 无检索结果时加入提示
        if ("知识库中暂无相关内容".equals(context)) {
            finalSystem += "\n知识库中暂无相关内容，请如实告知用户，不要编造信息。";
        }

        // 构建用户消息：将上下文包裹在用户问题之后（核心修复）
        // 参照 Spring AI QuestionAnswerAdvisor 的标准格式，
        // 让上下文与用户问题紧密关联，LLM无法忽略
        String augmentedUser;
        if (context != null && !context.isEmpty()
                && !"知识库中暂无相关内容".equals(context)) {
            augmentedUser = question + "\n\n" +
                    "Context information is below, surrounded by ---------------------\n" +
                    "\n" +
                    "---------------------\n" +
                    context + "\n" +
                    "---------------------\n" +
                    "\n" +
                    "Given the context and provided history information and not prior knowledge, " +
                    "reply to the user comment. If the answer is not in the context, inform " +
                    "the user that you can't answer the question.";
        } else {
            augmentedUser = question;
        }

        log.info("[LLMGenerate] 开始生成, conversationId={}, systemPrompt={}字, context={}字, userMsg={}字",
                conversationId, finalSystem.length(), context.length(), augmentedUser.length());

        try {
            String answer = chatClient.prompt()
                    .system(finalSystem)
                    .user(augmentedUser)
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param("chat_memory_response_size", CHAT_MEMORY_RESPONSE_SIZE))
                    .call()
                    .content();

            if (answer == null || answer.trim().isEmpty()) {
                answer = "抱歉，暂时无法回答您的问题。";
            }

            log.info("[LLMGenerate] 生成完成, answer={}字", answer.length());

            return Map.of(
                    StateKeys.ANSWER, answer,
                    StateKeys.STEPS, "答案生成完成"
            );

        } catch (Exception e) {
            log.error("[LLMGenerate] 生成失败: {}", e.getMessage());
            return Map.of(
                    StateKeys.ANSWER, "抱歉，发生了错误，请稍后重试。",
                    StateKeys.STEPS, "生成失败: " + e.getMessage()
            );
        }
    }
}
