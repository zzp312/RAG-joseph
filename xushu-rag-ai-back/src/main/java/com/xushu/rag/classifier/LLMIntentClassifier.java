package com.xushu.rag.classifier;

import com.xushu.rag.structured.IntentClassification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * L2 LLM 轻量意图分类（~200 token, 仅处理L1未命中的长尾请求）
 * <p>使用BeanOutputConverter实现结构化输出，要求LLM返回JSON后自动反序列化</p>
 * <p>fallback: 若结构化解析失败，回退到原有的trim+枚举关键词匹配</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class LLMIntentClassifier implements IntentClassifier {

    private final ChatClient chatClient;
    private final BeanOutputConverter<IntentClassification> converter;

    private static final String CLASSIFY_PROMPT = """
            判断用户意图和情绪，返回JSON格式：
            {"intent":"分类","reason":"理由简述","confidence":置信度,"emotion":"情绪"}

            意图分类选项：
            calculation - 计算类（工资、补偿、天数、费用等需要数值计算）
            reference  - 资料查阅类（定义、规定、流程、制度等知识查询）
            operation  - 操作类（入职、离职、请假、查询信息、合同签署等业务办理）
            escalation - 转人工（用户明确要求"转人工""找人工客服""叫人来"）
            chitchat   - 闲聊（问候、感谢、无关话题）

            情绪判断选项：
            positive - 积极、满意
            neutral  - 中性、正常
            negative - 负面、不满、不耐烦、愤怒

            置信度: 0.0~1.0（对分类判断的确信程度）
            """;

    public LLMIntentClassifier(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(CLASSIFY_PROMPT)
                .build();
        this.converter = new BeanOutputConverter<>(IntentClassification.class);
    }

    @Override
    public ClassifyResult classify(String question) {
        try {
            String format = converter.getFormat();
            String userPrompt = question + "\n" + format;

            String result = chatClient.prompt()
                    .user(userPrompt)
                    .options(ChatOptions.builder()
                            .maxTokens(150)
                            .build())
                    .call()
                    .content();

            if (result == null || result.trim().isEmpty()) {
                return new ClassifyResult(Category.UNKNOWN, "L2", 200);
            }

            // 【主路径】BeanOutputConverter结构化解析
            try {
                IntentClassification intentResult = converter.convert(result);
                Category category = Category.fromValue(intentResult.intent());
                String emotion = intentResult.emotion() != null ? intentResult.emotion() : "neutral";
                log.info("[L2 LLM] 结构化分类完成 category={}, confidence={}, reason={}, emotion={}",
                        category, intentResult.confidence(), intentResult.reason(), emotion);
                return new ClassifyResult(category, "L2", (int) (intentResult.confidence()) * 100, emotion);
            } catch (Exception parseEx) {
                // 【fallback】结构化解析失败，回退原有逻辑
                log.warn("[L2 LLM] BeanOutputConverter解析失败，回退字符串匹配: {}", parseEx.getMessage());
                String trimmed = result.trim().toLowerCase();
                Category category = Category.fromValue(trimmed);
                log.info("[L2 LLM] fallback分类完成 category={}, raw={}", category, trimmed);
                return new ClassifyResult(category, "L2", 200);
            }

        } catch (Exception e) {
            log.warn("[L2 LLM] 分类失败, 默认unknown: {}", e.getMessage());
            return new ClassifyResult(Category.UNKNOWN, "L2", 200);
        }
    }
}
