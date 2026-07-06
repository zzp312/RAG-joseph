package com.xushu.rag.classifier;

/**
 * 意图分类策略接口（策略模式）
 * <p>L1关键词快速路由 + L2 LLM轻量分类 实现两层漏斗</p>
 *
 * @author Joseph
 */
public interface IntentClassifier {

    /**
     * 意图类别枚举
     */
    enum Category {
        /** 计算类 */
        CALCULATION("calculation"),
        /** 资料查阅类 */
        REFERENCE("reference"),
        /** 操作类 */
        OPERATION("operation"),
        /** 转人工 */
        ESCALATION("escalation"),
        /** 闲聊 */
        CHITCHAT("chitchat"),
        /** 规划类（旅游计划、方案设计、行程安排等需要基于素材推理整合的任务） */
        PLANNING("planning"),
        /** 未知 */
        UNKNOWN("unknown");

        private final String value;

        Category(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Category fromValue(String value) {
            if (value == null) return UNKNOWN;
            for (Category c : values()) {
                if (c.value.equalsIgnoreCase(value.trim())) {
                    return c;
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * 分类结果
     */
    class ClassifyResult {
        private final Category category;
        private final String layer; // L1 或 L2
        private final int tokenUsed;
        private final String emotion; // positive/neutral/negative, L1默认neutral
        private final boolean toolConfirm; // 用户是否确认调用工具（operation类意图时有意义）
        private final String targetMcpServer; // 目标MCP服务名（intent=operation时由LLM识别，无法判断则为null）

        public ClassifyResult(Category category, String layer, int tokenUsed) {
            this(category, layer, tokenUsed, "neutral", false, null);
        }

        public ClassifyResult(Category category, String layer, int tokenUsed, String emotion) {
            this(category, layer, tokenUsed, emotion, false, null);
        }

        public ClassifyResult(Category category, String layer, int tokenUsed, String emotion, boolean toolConfirm) {
            this(category, layer, tokenUsed, emotion, toolConfirm, null);
        }

        public ClassifyResult(Category category, String layer, int tokenUsed, String emotion, boolean toolConfirm, String targetMcpServer) {
            this.category = category;
            this.layer = layer;
            this.tokenUsed = tokenUsed;
            this.emotion = emotion;
            this.toolConfirm = toolConfirm;
            this.targetMcpServer = targetMcpServer;
        }

        public Category getCategory() {
            return category;
        }

        public String getLayer() {
            return layer;
        }

        public int getTokenUsed() {
            return tokenUsed;
        }

        public String getEmotion() {
            return emotion;
        }

        public boolean isToolConfirm() {
            return toolConfirm;
        }

        public String getTargetMcpServer() {
            return targetMcpServer;
        }

        @Override
        public String toString() {
            return "ClassifyResult{category=" + category + ", layer=" + layer + ", tokenUsed=" + tokenUsed
                    + ", emotion=" + emotion + ", toolConfirm=" + toolConfirm
                    + ", targetMcpServer=" + targetMcpServer + "}";
        }
    }

    /**
     * 执行意图分类
     *
     * @param question 用户提问
     * @return 分类结果
     */
    ClassifyResult classify(String question);

    /**
     * 执行意图分类（带对话历史）
     * <p>用于需要上下文判断的场景（如判断用户是否在确认上轮推荐的工具调用）</p>
     *
     * @param question 用户提问
     * @param history 对话历史文本（可为空）
     * @return 分类结果
     */
    default ClassifyResult classify(String question, String history) {
        // 默认实现：忽略history，调用无参版本
        return classify(question);
    }
}
