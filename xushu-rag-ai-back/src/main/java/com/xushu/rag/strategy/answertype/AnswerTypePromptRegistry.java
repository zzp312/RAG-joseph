package com.xushu.rag.strategy.answertype;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 答案形态提示词策略注册表。
 * <p>Spring 自动注入所有 {@link AnswerTypePromptStrategy} Bean，
 * 按 answerType → strategy 映射。未匹配的形态走 {@link DescriptionAnswerTypeStrategy}（默认透传）。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class AnswerTypePromptRegistry {

    /** answerType → strategy 映射 */
    private final Map<String, AnswerTypePromptStrategy> strategyMap;

    /** 默认策略（兜底所有未匹配的形态） */
    private final AnswerTypePromptStrategy defaultStrategy;

    public AnswerTypePromptRegistry(List<AnswerTypePromptStrategy> allStrategies) {
        this.strategyMap = new HashMap<>();

        // 找到默认策略（支持 "description" 的那个）
        AnswerTypePromptStrategy found = allStrategies.stream()
                .filter(s -> "description".equals(s.supportedAnswerType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "DescriptionAnswerTypeStrategy not found — 至少需要一个默认策略"));
        this.defaultStrategy = found;

        // 注册所有策略
        for (AnswerTypePromptStrategy strategy : allStrategies) {
            strategyMap.put(strategy.supportedAnswerType(), strategy);
        }

        log.info("[AnswerTypePrompt] 注册 {} 个答案形态策略: {}",
                allStrategies.size(), strategyMap.keySet());
    }

    /**
     * 根据答案形态解析对应策略。
     *
     * @param answerType 答案形态（如 "boolean"、"single_value"、"list"）
     * @return 对应策略，未匹配返回默认透传策略
     */
    public AnswerTypePromptStrategy resolve(String answerType) {
        return strategyMap.getOrDefault(answerType, defaultStrategy);
    }
}
