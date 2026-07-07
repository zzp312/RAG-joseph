package com.xushu.rag.strategy.answertype;

import org.springframework.stereotype.Component;

/**
 * 单值型（具体数值）答案形态策略。
 * <p>约束：数值结论 + 单位标注 + 来源引用 + 可选补充。
 * 不限制内容量，只规范输出结构。</p>
 *
 * @author Joseph
 */
@Component
public class SingleValueAnswerTypeStrategy implements AnswerTypePromptStrategy {

    @Override
    public String supportedAnswerType() {
        return "single_value";
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        return systemPrompt + """


                【单值型问题专用约束】
                1. 数值结论：先用一句话给出目标数值（必须带单位）。
                2. 来源标注：说明数值来自哪个文档/段落。
                3. 补充信息：如果上下文中有同比数据、统计口径说明等关联信息，
                   可以简要补充，帮助用户全面理解数据。
                4. 缺失处理：如果上下文中无该指标，回答"知识库中暂无该数据"，
                   不要用近似指标替代。
                """;
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail + "\n\n"
                + "【输出要求】先给出数值结论（含单位），再简要补充来源和关联信息（如有）。"
                + "示例格式：\"2024年营业收入为1,234.56万元（数据来源：2024年度报告），"
                + "同比增长约12.2%。\"";
    }
}
