package com.xushu.rag.structured;

/**
 * LLM意图分类的结构化输出Record（用于BeanOutputConverter）
 * <p>由LLM返回JSON后自动反序列化为此类型，同时输出意图和情绪</p>
 *
 * @author Joseph
 */
public record IntentClassification(
        /** 意图类别：calculation / reference / operation / chitchat */
        String intent,
        /** 分类理由简述 */
        String reason,
        /** 置信度 0~1 */
        double confidence,
        /** 用户情绪：positive / neutral / negative（旧版LLM可能不返回，调用方兜底neutral） */
        String emotion
) {}
