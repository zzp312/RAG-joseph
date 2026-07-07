package com.xushu.rag.strategy.answertype;

import org.springframework.stereotype.Component;

/**
 * 名单/列表型答案形态策略。
 * <p>约束：逐条列出 + 属性标注 + 完整性声明。</p>
 *
 * @author Joseph
 */
@Component
public class ListAnswerTypeStrategy implements AnswerTypePromptStrategy {

    @Override
    public String supportedAnswerType() {
        return "list";
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        return systemPrompt + """


                【名单类问题专用约束】
                1. 穷举原则：列出上下文中所有符合条件的条目，不得遗漏任何一条。
                2. 属性标注：每个条目附上必要属性（如职务、任期、金额、时间等），
                   让用户无需追问即可了解全貌。
                3. 格式清晰：逐条列出，便于阅读。
                4. 完整性声明：如果上下文可能不完整，在文末注明"以上为已知信息，可能不完整"。
                5. 禁止编造：只列出上下文明确出现的实体，不补充上下文之外的内容。
                """;
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail + "\n\n"
                + "【输出要求】用Markdown列表格式逐条列出所有条目，每个条目标注属性。\n"
                + "要求：\n"
                + "1. 每个条目用\"- \"开头（减号+空格），不要用数字序号\n"
                + "2. 列表结束后空一行再写补充说明\n"
                + "3. 如果可能不完整，在文末注明\n\n"
                + "示例格式：\n"
                + "- 张三（董事长，任期2022-2025）\n"
                + "- 李四（独立董事，任期2023-2026）\n"
                + "\n"
                + "以上信息基于2024年年报披露，可能不完整。";
    }
}
