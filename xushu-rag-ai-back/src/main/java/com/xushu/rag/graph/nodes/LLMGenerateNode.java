package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.strategy.AnswerGenerationStrategy;
import com.xushu.rag.strategy.AnswerStrategyRegistry;
import com.xushu.rag.strategy.AnswerGenerationStrategy.ValidationResult;
import com.xushu.rag.strategy.answertype.AnswerTypePromptRegistry;
import com.xushu.rag.strategy.answertype.AnswerTypePromptStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM生成Node
 * <p>调用ChatClient生成最终答案，使用提示词模板 + RAG上下文</p>
 * <p>内置对话记忆（PromptChatMemoryAdvisor），保留最近5轮对话历史，
 * 切换知识库/文件时由GraphChatController通过fingerprint检测自动清除</p>
 * <p>提示词组装链路：基础模板 → 策略层(category)指令 → 形态层(answerType)指令 → 用户消息+上下文</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class LLMGenerateNode {

    /** 对话记忆保留消息数（5轮 = 10条消息） */
    private static final int CHAT_MEMORY_RESPONSE_SIZE = 10;

    /** 提示词日志截断长度 */
    private static final int PROMPT_LOG_MAX_LENGTH = 3000;

    /** RAGAS 异步评估开关（默认关闭，开发调试时开启） */
    @Value("${ragas.eval.enabled:false}")
    private boolean ragasEvalEnabled;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AnswerStrategyRegistry strategyRegistry;
    private final AnswerTypePromptRegistry answerTypeRegistry;

    public LLMGenerateNode(ChatModel chatModel, ChatMemory chatMemory,
                           AnswerStrategyRegistry strategyRegistry,
                           AnswerTypePromptRegistry answerTypeRegistry) {
        this.chatMemory = chatMemory;
        this.strategyRegistry = strategyRegistry;
        this.answerTypeRegistry = answerTypeRegistry;
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
        String category = (String) state.getOrDefault(StateKeys.CATEGORY, "");
        String answerType = (String) state.getOrDefault(StateKeys.ANSWER_TYPE, "description");

        // 根据意图类别解析答案生成策略（CoT + 结构化输出 + 引用校验）
        AnswerGenerationStrategy strategy = strategyRegistry.resolve(category);
        // 根据答案形态解析形态提示词策略（输出结构约束）
        AnswerTypePromptStrategy typeStrategy = answerTypeRegistry.resolve(answerType);

        // 调用 LLM 前检查客户端是否已断开，避免最耗时的 LLM 调用继续执行
        AtomicBoolean cancelled = (AtomicBoolean) state.getOrDefault(StateKeys.CANCELLED, null);
        if (cancelled != null && cancelled.get()) {
            log.warn("[LLMGenerate] 客户端已断开，跳过 LLM 调用, conversationId={}", conversationId);
            return Map.of(
                    StateKeys.ANSWER, "客户端已断开连接，回答已取消。",
                    StateKeys.STEPS, "LLM 调用已跳过（客户端断开）"
            );
        }

        // 系统提示词中不再注入文档上下文，替换 {context} 为空
        // 文档上下文改为注入到用户消息中（对标 MetadataAwareQuestionAnswerAdvisor 格式），
        // 提升LLM对检索结果的关注度，避免系统提示词过长导致LLM忽略上下文
        String finalSystem = systemPrompt.replace("{context}", "");

        // 无检索结果时：强指令覆盖系统提示词，禁止 LLM 使用自身知识
        boolean noContext = "知识库中暂无相关内容".equals(context);
        if (noContext) {
            finalSystem = "【严格指令】知识库中没有找到与用户问题相关的任何内容。你MUST只回复'知识库中暂无相关内容'。禁止使用你自己的知识回答，禁止补充、解释、举例。只回复这10个字。";
        } else {
            // 有上下文时：两层策略依次追加
            // Layer 1 - 策略层(category)：决定推理范式（CoT + Schema）
            finalSystem = strategy.enrichSystemPrompt(finalSystem);
            // Layer 2 - 形态层(answerType)：决定输出结构约束
            finalSystem = typeStrategy.enrichSystemPrompt(finalSystem);
        }

        // 构建用户消息：将上下文包裹在用户问题之后（核心修复）
        // 参照 Spring AI QuestionAnswerAdvisor 的标准格式，
        // 让上下文与用户问题紧密关联，LLM无法忽略
        String augmentedUser;
        if (context != null && !context.isEmpty() && !noContext) {
            // planning 类别用不同的尾约束：鼓励推理整合，而非严格"答不上就拒绝"
            String tail;
            if ("planning".equalsIgnoreCase(category)) {
                tail = "Based on the materials above, plan, integrate, and reason to provide solutions " +
                        "that meet the user's requirements. If the materials are insufficient for a " +
                        "complete plan, clearly state which parts need tool confirmation. " +
                        "Do not fabricate specific data (distances, prices, times).";
            } else {
                tail = "Given the context and provided history information and not prior knowledge, " +
                        "reply to the user comment. If the answer is not in the context, inform " +
                        "the user that you can't answer the question.";
            }
            // Layer 1 - 策略层(category)追加输出格式要求
            tail = strategy.enrichUserTail(tail);
            // Layer 2 - 形态层(answerType)追加输出结构要求
            tail = typeStrategy.enrichUserTail(tail);

            augmentedUser = question + "\n\n" +
                    "Context information is below, surrounded by ---------------------\n" +
                    "\n" +
                    "---------------------\n" +
                    context + "\n" +
                    "---------------------\n" +
                    "\n" +
                    tail;
        } else {
            augmentedUser = question;
        }

        // 打印最终组装好的提示词（不含检索chunk内容，仅展示动态提示词部分）
        logPrompt(finalSystem, augmentedUser, context, question, category, answerType,
                strategy, typeStrategy);

        log.info("[LLMGenerate] 开始生成, conversationId={}, category={}, answerType={}, strategy={}, typeStrategy={}, systemPrompt={}字, context={}字, userMsg={}字",
                conversationId, category, answerType,
                strategy.getClass().getSimpleName(), typeStrategy.getClass().getSimpleName(),
                finalSystem.length(), context.length(), augmentedUser.length());

        try {
            String rawOutput = chatClient.prompt()
                    .system(finalSystem)
                    .user(augmentedUser)
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param("chat_memory_response_size", CHAT_MEMORY_RESPONSE_SIZE))
                    .call()
                    .content();

            if (rawOutput == null || rawOutput.trim().isEmpty()) {
                return Map.of(
                        StateKeys.ANSWER, "抱歉，暂时无法回答您的问题。",
                        StateKeys.STEPS, "答案生成完成"
                );
            }

            // 策略提取最终答案（结构化策略用 BeanOutputConverter，对话类原样返回）
            String answer = strategy.extractAnswer(rawOutput);
            // 兜底：如果 LLM 意外输出了 JSON 包裹（如闲聊被误当结构化输出），
            // 统一提取 finalAnswer 字段，确保用户永远看不到原始 JSON
            answer = stripJsonWrapper(answer);

            // 澄清检测：LLM 在反问而非回答 → 强指令重新生成
            // （仅对非结构化输出做澄清检测，结构化 JSON 不会产生反问）
            if (isClarification(answer) && context != null && context.length() > 500) {
                log.info("[LLMGenerate] 检测到澄清式回答，触发强指令重新生成");
                String reRaw = chatClient.prompt()
                        .system("【强制指令】你是知识库助手，必须基于上下文完整回答用户问题。禁止反问、禁止要求用户澄清、禁止说'请问'。如果有多个条目，全部列出来。")
                        .user(augmentedUser)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                                .param("chat_memory_response_size", CHAT_MEMORY_RESPONSE_SIZE))
                        .call()
                        .content();
                if (reRaw != null && !reRaw.trim().isEmpty()) {
                    answer = strategy.extractAnswer(reRaw);
                    answer = stripJsonWrapper(answer);
                    if (!isClarification(answer)) {
                        log.info("[LLMGenerate] 强指令重新生成成功, answer={}字", answer.length());
                    }
                }
            }

            // 策略校验引用/数据幻觉（只告警不阻断）
            ValidationResult vr = strategy.validate(rawOutput, context);
            if (!vr.isValid()) {
                log.warn("[LLMGenerate] 校验告警: category={}, {}", category, vr.warningMsg());
            }

            // 提取 CoT 推理过程，供 SSE 推送前端"深度思考"展示
            String cotAnalysis = strategy.extractThinking(rawOutput);

            log.info("[LLMGenerate] 生成完成, answer={}字, cot={}字, question={}", answer.length(),
                    cotAnalysis != null ? cotAnalysis.length() : 0,
                    question.length() > 40 ? question.substring(0, 40) + "..." : question);

            // 异步评估已关闭
            // if (ragasEvalEnabled) { asyncEval(question, answer, context, chatClient); }

            return Map.of(
                    StateKeys.ANSWER, answer,
                    StateKeys.COT_ANALYSIS, cotAnalysis != null ? cotAnalysis : "",
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

    // ========== 私有辅助 ==========

    /**
     * 兜底反 JSON 包装：如果答案以 <code>{"stepByStepAnalysis"...</code> 开头，
     * 提取其中的 {@code finalAnswer} 字段作为最终答案，确保用户永远看不到原始 JSON。
     * <p>这个方法不依赖任何 JSON 库，纯字符串正则提取，零依赖零开销。</p>
     */
    public static String stripJsonWrapper(String answer) {
        if (answer == null || answer.length() < 10) {
            return answer;
        }
        String trimmed = answer.trim();
        // 必须以 { 开头且包含 "finalAnswer" 才处理，避免误杀普通文本
        if (!trimmed.startsWith("{") || !trimmed.contains("\"finalAnswer\"")) {
            return answer;
        }
        try {
            Pattern p = Pattern.compile("\"finalAnswer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(trimmed);
            if (m.find()) {
                String extracted = m.group(1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\r", "\r");
                log.info("[stripJsonWrapper] 从意外 JSON 输出中提取 finalAnswer, " +
                        "原{}字 → 提取{}字", trimmed.length(), extracted.length());
                return extracted;
            }
        } catch (Exception ignored) {
            // 提取失败时降级返回原文，不阻塞主流程
        }
        return answer;
    }

    /**
     * 打印最终组装好的提示词日志。
     * <p>System Prompt 完整打印；User Message 剥离检索chunk内容，
     * 只展示问题和动态拼装的 tail 指令部分，避免日志被大量chunk文本淹没。</p>
     */
    private void logPrompt(String finalSystem, String augmentedUser,
                           String context, String question,
                           String category, String answerType,
                           AnswerGenerationStrategy strategy,
                           AnswerTypePromptStrategy typeStrategy) {
        if (!log.isInfoEnabled()) {
            return;
        }

        // 剥离 chunk：取 question 和 tail 之间的部分
        // augmentedUser 格式：question + "\n\nContext information...\n-----\n" + context + "\n-----\n\n" + tail
        String userWithoutContext;
        if (context != null && !context.isEmpty()) {
            // 提取 question 部分和 tail 部分（去掉中间的 context）
            String beforeCtx = question;
            int tailStart = augmentedUser.lastIndexOf("---------------------\n");
            String afterCtx = "";
            if (tailStart != -1) {
                // tail 在最后一个 "---------------------\n" 之后
                int tailIdx = augmentedUser.indexOf("\n", tailStart + "---------------------\n".length());
                if (tailIdx != -1) {
                    afterCtx = augmentedUser.substring(tailIdx + 1).trim();
                }
            }
            userWithoutContext = beforeCtx + "\n\n" +
                    "[检索上下文已省略，共 " + context.length() + " 字符]\n\n" +
                    (afterCtx.isEmpty() ? "" : afterCtx);
        } else {
            userWithoutContext = augmentedUser;
        }

        // 截断过长的内容
        String systemLog = finalSystem.length() > PROMPT_LOG_MAX_LENGTH
                ? finalSystem.substring(0, PROMPT_LOG_MAX_LENGTH) + "\n...[已截断，全文" + finalSystem.length() + "字]"
                : finalSystem;
        String userLog = userWithoutContext.length() > PROMPT_LOG_MAX_LENGTH
                ? userWithoutContext.substring(0, PROMPT_LOG_MAX_LENGTH) + "\n...[已截断]"
                : userWithoutContext;

        log.info("\n" +
                "╔══════════════════════════════════════════════════════════════╗\n" +
                "║  [LLM Prompt] 最终组装提示词                                  ║\n" +
                "║  category={} | answerType={} | strategy={} | typeStrategy={} ║\n" +
                "╠══════════════════ SYSTEM PROMPT ═════════════════════════════╣\n" +
                "{}\n" +
                "╠══════════════════ USER MESSAGE (不含检索chunk) ═════════════╣\n" +
                "{}\n" +
                "╚══════════════════════════════════════════════════════════════╝",
                category, answerType,
                strategy.getClass().getSimpleName(), typeStrategy.getClass().getSimpleName(),
                systemLog, userLog);
    }

    /** 检测回答是否为反问/澄清式（40字内且含疑问词） */
    private static boolean isClarification(String answer) {
        if (answer == null || answer.length() > 60) return false;
        return answer.contains("请问") || answer.contains("您想了解") || answer.contains("具体是")
                || answer.contains("哪个方面") || answer.contains("能详细");
    }
}
