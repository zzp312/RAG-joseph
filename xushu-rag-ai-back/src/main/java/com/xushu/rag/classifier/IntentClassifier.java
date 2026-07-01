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
        /** 闲聊 */
        CHITCHAT("chitchat"),
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

        public ClassifyResult(Category category, String layer, int tokenUsed) {
            this.category = category;
            this.layer = layer;
            this.tokenUsed = tokenUsed;
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

        @Override
        public String toString() {
            return "ClassifyResult{category=" + category + ", layer=" + layer + ", tokenUsed=" + tokenUsed + "}";
        }
    }

    /**
     * 执行意图分类
     *
     * @param question 用户提问
     * @return 分类结果
     */
    ClassifyResult classify(String question);
}
