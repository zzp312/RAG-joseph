package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
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

    /** RAGAS 异步评估开关（默认关闭，开发调试时开启） */
    @Value("${ragas.eval.enabled:false}")
    private boolean ragasEvalEnabled;

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

        // 无检索结果时：强指令覆盖系统提示词，禁止 LLM 使用自身知识
        if ("知识库中暂无相关内容".equals(context)) {
            finalSystem = "【严格指令】知识库中没有找到与用户问题相关的任何内容。你MUST只回复'知识库中暂无相关内容'。禁止使用你自己的知识回答，禁止补充、解释、举例。只回复这10个字。";
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

            // 澄清检测：LLM 在反问而非回答 → 强指令重新生成
            if (isClarification(answer) && context != null && context.length() > 500) {
                log.info("[LLMGenerate] 检测到澄清式回答，触发强指令重新生成");
                String reAnswer = chatClient.prompt()
                        .system("【强制指令】你是知识库助手，必须基于上下文完整回答用户问题。禁止反问、禁止要求用户澄清、禁止说'请问'。如果有多个条目，全部列出来。")
                        .user(augmentedUser)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                                .param("chat_memory_response_size", CHAT_MEMORY_RESPONSE_SIZE))
                        .call()
                        .content();
                if (reAnswer != null && !reAnswer.trim().isEmpty() && !isClarification(reAnswer)) {
                    answer = reAnswer;
                    log.info("[LLMGenerate] 强指令重新生成成功, answer={}字", answer.length());
                }
            }

            log.info("[LLMGenerate] 生成完成, answer={}字, question={}", answer.length(),
                    question.length() > 40 ? question.substring(0, 40) + "..." : question);

            // 异步评估已关闭
            // if (ragasEvalEnabled) { asyncEval(question, answer, context, chatClient); }

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

    /** 检测回答是否为反问/澄清式（40字内且含疑问词） */
    private static boolean isClarification(String answer) {
        if (answer == null || answer.length() > 60) return false;
        return answer.contains("请问") || answer.contains("您想了解") || answer.contains("具体是")
                || answer.contains("哪个方面") || answer.contains("能详细");
    }
}
