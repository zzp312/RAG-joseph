package com.xushu.rag.classifier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * L2 LLM 轻量意图分类（~200 token, 仅处理L1未命中的长尾请求）
 * <p>调用DashScope qwen-turbo做文本分类，只返回一个分类词</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class LLMIntentClassifier implements IntentClassifier {

    private final ChatClient chatClient;

    private static final String CLASSIFY_PROMPT = """
            判断用户意图，只返回一个单词：
            calculation - 计算类（工资、补偿、天数、费用等需要数值计算）
            reference  - 资料查阅类（定义、规定、流程、制度等知识查询）
            operation  - 操作类（入职、离职、请假、合同签署等业务办理）
            chitchat   - 闲聊（问候、感谢、无关话题）
            """;

    public LLMIntentClassifier(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(CLASSIFY_PROMPT)
                .build();
    }

    @Override
    public ClassifyResult classify(String question) {
        try {
            String result = chatClient.prompt()
                    .user(question)
                    .options(org.springframework.ai.chat.prompt.ChatOptions.builder()
                            .maxTokens(10)
                            .build())
                    .call()
                    .content();

            if (result == null || result.trim().isEmpty()) {
                return new ClassifyResult(Category.UNKNOWN, "L2", 200);
            }

            String trimmed = result.trim().toLowerCase();
            Category category = Category.fromValue(trimmed);
            log.info("[L2 LLM] 分类完成 category={}, raw={}", category, trimmed);
            return new ClassifyResult(category, "L2", 200);

        } catch (Exception e) {
            log.warn("[L2 LLM] 分类失败, 默认unknown: {}", e.getMessage());
            return new ClassifyResult(Category.UNKNOWN, "L2", 200);
        }
    }
}
