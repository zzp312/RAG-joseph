package com.xushu.rag.service;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * 重排序策略接口 —— 策略模式
 * <p>
 * 当前默认实现：{@link com.xushu.rag.service.impl.VersionFirstRerankStrategy}
 * Phase 2 新增：{@code LLMRerankStrategy} —— 使用LLM对文档相关性打分重排
 * <p>
 * 切换方式：实现本接口并注册为Spring Bean即可零侵入替换策略
 * </p>
 *
 * @author Joseph
 */
public interface IRerankStrategy {

    /**
     * 对检索结果进行重排序
     *
     * @param documents 原始检索结果列表
     * @param context   检索上下文（query、kbIds等，供Phase 2 LLM重排序使用）
     * @return 重排序后的文档列表
     */
    List<Document> rerank(List<Document> documents, Map<String, Object> context);
}
