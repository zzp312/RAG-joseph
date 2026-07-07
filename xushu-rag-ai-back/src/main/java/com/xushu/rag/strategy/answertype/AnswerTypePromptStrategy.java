package com.xushu.rag.strategy.answertype;

/**
 * 答案形态提示词策略接口 — 按 answerType 差异化为 systemPrompt 和 userTail 追加专用指令。
 * <p>Spring Bean 自动发现 + {@link AnswerTypePromptRegistry} 路由。</p>
 * <p>每个实现声明 {@link #supportedAnswerType()}，Registry 遍历匹配，
 * 未命中走 {@link DescriptionAnswerTypeStrategy}（默认透传）。</p>
 * <p>与 {@link com.xushu.rag.strategy.AnswerGenerationStrategy} 的关系：
 * 前者按意图类别（category）决定推理范式，本接口按答案形态（answerType）决定输出结构。
 * 两者在 LLMGenerateNode 中依次调用，正交叠加。</p>
 *
 * @author Joseph
 */
public interface AnswerTypePromptStrategy {

    /** 本策略支持的答案形态 */
    String supportedAnswerType();

    /**
     * 在系统提示词末尾追加形态专属的推理约束指令。
     * @param systemPrompt 当前已拼装好的系统提示词（含策略层指令）
     * @return 增强后的系统提示词
     */
    String enrichSystemPrompt(String systemPrompt);

    /**
     * 在用户消息 tail 末尾追加形态专属的输出结构要求。
     * @param tail 当前已拼装好的 tail（含策略层格式要求）
     * @return 增强后的 tail
     */
    String enrichUserTail(String tail);
}
