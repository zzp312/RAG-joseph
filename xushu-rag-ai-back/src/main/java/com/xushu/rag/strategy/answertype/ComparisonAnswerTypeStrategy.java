package com.xushu.rag.strategy.answertype;

import org.springframework.stereotype.Component;

/**
 * 对比型答案形态策略。
 * <p>约束：逐维度对比 + 差异总结。</p>
 *
 * @author Joseph
 */
@Component
public class ComparisonAnswerTypeStrategy implements AnswerTypePromptStrategy {

    @Override
    public String supportedAnswerType() {
        return "comparison";
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        return systemPrompt + """


                【对比类问题专用约束】
                1. 逐维度对比：按维度分别说明各方的数据/情况，不混为一谈。
                2. 公平原则：每个维度对双方都进行说明，不偏向任何一方。
                3. 差异总结：最后用一两句话概括核心差异。
                4. 数据缺失：如果某一方缺少某维度的数据，明确注明"该方暂无此数据"。
                """;
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail + "\n\n"
                + "【输出要求】逐维度对比，每个维度分别说明各方情况，最后总结差异。";
    }
}
