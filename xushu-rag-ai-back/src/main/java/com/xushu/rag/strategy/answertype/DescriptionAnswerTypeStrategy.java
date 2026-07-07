package com.xushu.rag.strategy.answertype;

import org.springframework.stereotype.Component;

/**
 * 描述型答案形态策略（默认透传，不追加额外约束）。
 * <p>适用于"流程是什么""定义是什么"等开放式描述类问题，
 * 已有 CoT 策略足够，本策略不做额外干预。</p>
 *
 * @author Joseph
 */
@Component
public class DescriptionAnswerTypeStrategy implements AnswerTypePromptStrategy {

    @Override
    public String supportedAnswerType() {
        return "description";
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        return systemPrompt;
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail;
    }
}
