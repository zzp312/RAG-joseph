package com.xushu.rag.strategy;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话类意图策略（透传模式）。
 * <p>不做 CoT 要求、不做结构化输出、不做引用校验，LLM 输出原样返回。</p>
 * <p>覆盖：operation / chitchat / escalation</p>
 *
 * @author Joseph
 */
@Component
public class ConversationalAnswerStrategy implements AnswerGenerationStrategy {

    @Override
    public List<String> supportedCategories() {
        return List.of("operation", "chitchat", "escalation");
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        return systemPrompt;
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail;
    }

    @Override
    public String extractAnswer(String rawOutput) {
        // 防御：如果 LLM 异常输出了 JSON，提取 finalAnswer 字段
        return com.xushu.rag.graph.nodes.LLMGenerateNode.stripJsonWrapper(rawOutput);
    }

    @Override
    public String extractThinking(String rawOutput) {
        return null;
    }

    @Override
    public ValidationResult validate(String rawOutput, String context) {
        return ValidationResult.allGood();
    }
}
