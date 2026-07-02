package com.xushu.rag.structured;

/**
 * RAGAS评估打分的结构化输出Record（用于BeanOutputConverter）
 * <p>LLM-as-Judge返回的评分结果</p>
 *
 * @author Joseph
 */
public record EvalScore(
        /** 评估分数 0~1 */
        double score
) {}
