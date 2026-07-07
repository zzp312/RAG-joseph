package com.xushu.rag.strategy;

import java.util.List;

/**
 * 答案生成策略接口 — 按意图类别差异化 CoT 指令、结构化输出、引用校验。
 * <p>Spring Bean 自动发现 + {@link AnswerStrategyRegistry} 路由。</p>
 * <p>每个实现声明 {@link #supportedCategories()}，Registry 遍历匹配，
 * 未命中走 {@link RetrievalAnswerStrategy}（默认策略）。</p>
 *
 * @author Joseph
 */
public interface AnswerGenerationStrategy {

    // ========== 路由 ==========

    /** 本策略支持的意图类别列表 */
    List<String> supportedCategories();

    // ========== Prompt 增强 ==========

    /**
     * 在系统提示词末尾追加 CoT/Schema 指令。
     * @param systemPrompt PromptRouteNode 输出的基础系统提示词
     * @return 增强后的系统提示词
     */
    String enrichSystemPrompt(String systemPrompt);

    /**
     * 在用户消息 tail 末尾追加输出格式要求。
     * @param tail LLMGenerateNode 构建的基础 tail
     * @return 增强后的 tail
     */
    String enrichUserTail(String tail);

    // ========== 结果提取 ==========

    /**
     * 从 LLM 原始输出中提取面向用户的最终答案。
     * 结构化策略用 BeanOutputConverter 解析 JSON → 取 finalAnswer，
     * 对话类策略原样返回。
     *
     * @param rawOutput LLM 原始输出字符串
     * @return 面向用户的最终答案文本
     */
    String extractAnswer(String rawOutput);

    /**
     * 从 LLM 原始输出中提取 CoT 推理过程，供前端"深度思考"展示。
     * 非结构化策略返回 null。
     *
     * @param rawOutput LLM 原始输出字符串
     * @return CoT 推理文本，无推理时返回 null
     */
    String extractThinking(String rawOutput);

    // ========== 事后校验 ==========

    /**
     * 校验答案中的引用/数据是否真实存在于检索上下文中。
     *
     * @param rawOutput LLM 原始输出
     * @param context   检索上下文文本（ContextBuildNode 输出）
     * @return 校验报告
     */
    ValidationResult validate(String rawOutput, String context);

    /** 校验结果 */
    record ValidationResult(boolean isValid, String warningMsg) {
        public static ValidationResult allGood() {
            return new ValidationResult(true, null);
        }
        public static ValidationResult warn(String msg) {
            return new ValidationResult(false, msg);
        }
    }
}
