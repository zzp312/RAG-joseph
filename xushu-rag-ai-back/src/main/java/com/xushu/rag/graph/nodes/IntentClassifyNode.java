package com.xushu.rag.graph.nodes;

import com.xushu.rag.classifier.IntentClassifier;
import com.xushu.rag.classifier.KeywordIntentClassifier;
import com.xushu.rag.classifier.LLMIntentClassifier;
import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 意图分类Node（两层漏斗：L1关键词 + L2 LLM）
 * <p>
 * L1关键词：0ms延迟，0 token，覆盖高频问候/指示词
 * L2 LLM：~200 token，处理长尾请求
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class IntentClassifyNode {

    private final KeywordIntentClassifier keywordClassifier;
    private final LLMIntentClassifier llmClassifier;

    public IntentClassifyNode(KeywordIntentClassifier keywordClassifier,
                              LLMIntentClassifier llmClassifier) {
        this.keywordClassifier = keywordClassifier;
        this.llmClassifier = llmClassifier;
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
        if (question.trim().isEmpty()) {
            return Map.of(StateKeys.CATEGORY, "chitchat");
        }

        // L1: 关键词快速路由（0 token）
        IntentClassifier.ClassifyResult result = keywordClassifier.classify(question);
        if (result == null) {
            // L2: LLM兜底（~200 token）
            result = llmClassifier.classify(question);
        }

        String category = result.getCategory().getValue();
        log.info("[IntentClassify] question='{}', category={}, layer={}, tokenUsed={}",
                question.length() > 40 ? question.substring(0, 40) + "..." : question,
                category, result.getLayer(), result.getTokenUsed());

        return Map.of(
                StateKeys.CATEGORY, category,
                StateKeys.STEPS, "意图分类完成: " + category + " (" + result.getLayer() + ")"
        );
    }
}
