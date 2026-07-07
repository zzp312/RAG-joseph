package com.xushu.rag.strategy.answertype;

import org.springframework.stereotype.Component;

/**
 * 布尔型（是/否）答案形态策略。
 * <p>约束：结论前置 + 证据引用 + 可选补充说明。
 * 不限制内容量，只规范输出结构。</p>
 *
 * @author Joseph
 */
@Component
public class BooleanAnswerTypeStrategy implements AnswerTypePromptStrategy {

    @Override
    public String supportedAnswerType() {
        return "boolean";
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        return systemPrompt + """


                【布尔型问题专用约束】
                1. 结论前置：回答的第一句话必须是明确的"是"或"否"，不加修饰词。
                2. 证据句：紧接着引用上下文中的一句原文作为判断依据。
                3. 补充说明：如果上下文中有相关细节（如具体金额、时间、条件），
                   可以简要补充，帮助用户理解判断依据的全貌。
                4. 无法判断时：回答"知识库中暂无相关信息，无法判断"。
                """;
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail + "\n\n"
                + "【输出要求】先明确给出[是]或[否]的结论，然后引用原文证据。"
                + "如有补充信息可简要说明。"
                + "示例格式：[是。根据2024年分红公告，公司每10股派发现金红利3.5元。]";
    }
}
