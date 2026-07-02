package com.xushu.rag.structured;

/**
 * LLM意图分类的结构化输出Record（用于BeanOutputConverter）
 * <p>由LLM返回JSON后自动反序列化为此类型</p>
 *
 * @author Joseph
 */
public record IntentClassification(
        /** 意图类别：calculation / reference / operation / chitchat */
        String intent,
        /** 分类理由简述 */
        String reason,
        /** 置信度 0~1 */
        double confidence
) {}
