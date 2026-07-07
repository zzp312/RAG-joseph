package com.xushu.rag.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 答案生成策略注册表。
 * <p>Spring 自动注入所有 {@link AnswerGenerationStrategy} Bean，
 * 按 category → strategy 映射。未匹配的意图走 {@link RetrievalAnswerStrategy}（默认策略）。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class AnswerStrategyRegistry {

    /** category → strategy 映射 */
    private final Map<String, AnswerGenerationStrategy> strategyMap;

    /** 默认策略（兜底所有未匹配的意图） */
    private final AnswerGenerationStrategy defaultStrategy;

    /**
     * Spring 构造注入：收集所有 AnswerGenerationStrategy Bean
     */
    public AnswerStrategyRegistry(List<AnswerGenerationStrategy> allStrategies) {
        this.strategyMap = new HashMap<>();

        // 找到默认策略（支持 "reference" 的那个就是 RetrievalStrategy）
        AnswerGenerationStrategy found = allStrategies.stream()
                .filter(s -> s.supportedCategories().contains("reference"))
                .findFirst()
                .orElse(null);
        if (found == null) {
            throw new IllegalStateException(
                    "RetrievalAnswerStrategy not found — 至少需要一个默认策略");
        }
        this.defaultStrategy = found;

        // 注册所有策略
        for (AnswerGenerationStrategy strategy : allStrategies) {
            for (String cat : strategy.supportedCategories()) {
                strategyMap.put(cat, strategy);
            }
        }

        log.info("[AnswerStrategy] 注册 {} 个策略，覆盖 {} 个意图类别: {}",
                allStrategies.size(), strategyMap.size(), strategyMap.keySet());
    }

    /**
     * 根据意图类别解析对应策略。
     *
     * @param category 意图类别（如 "reference"、"planning"、"chitchat"）
     * @return 对应策略，未匹配返回默认 RetrievalAnswerStrategy
     */
    public AnswerGenerationStrategy resolve(String category) {
        return strategyMap.getOrDefault(category, defaultStrategy);
    }
}
