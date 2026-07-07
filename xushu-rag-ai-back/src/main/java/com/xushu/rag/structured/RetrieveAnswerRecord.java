package com.xushu.rag.structured;

import java.util.List;

/**
 * 检索类意图的结构化答案（用于 BeanOutputConverter）
 * <p>强制 LLM 输出 CoT 推理性 + 来源引用 + 最终答案</p>
 * <p>适用意图：reference / calculation / aggregation / comparison</p>
 *
 * @author Joseph
 */
public record RetrieveAnswerRecord(
        /**
         * 逐步推理过程，至少5步。
         * 每步说明从上下文中查到了什么、如何判断/计算/对比。
         * 如果答案不在上下文中，第1步就明确说明。
         */
        String stepByStepAnalysis,

        /**
         * 推理摘要，一句话概括（约50字）
         */
        String reasoningSummary,

        /**
         * 实际引用的来源列表。格式必须与上下文中"📚 可引用的文档来源"的条目完全一致。
         * 未引用任何来源时传空数组 []。
         */
        List<String> relevantSources,

        /**
         * 面向用户的最终答案
         */
        String finalAnswer
) {}
