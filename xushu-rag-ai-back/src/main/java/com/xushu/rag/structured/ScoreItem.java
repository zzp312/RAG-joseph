 package com.xushu.rag.structured;

/**
 * LLM重排序打分的结构化输出Record（用于BeanOutputConverter）
 * <p>单个文档的相关性分数，批量打分时使用List&lt;ScoreItem&gt;</p>
 *
 * @author Joseph
 */
public record ScoreItem(
        /** 相关性分数 0~1 */
        double score
) {}
