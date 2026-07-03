package com.xushu.rag.graph;

/**
 * Graph Agent 工作流 OverAllState 键名常量
 * <p>每个Node读写State时使用这些常量，避免硬编码字符串</p>
 *
 * @author Joseph
 */
public final class StateKeys {

    private StateKeys() {
    }

    /** 用户原始提问 */
    public static final String QUESTION = "question";

    /** 意图分类结果：calculation | reference | operation | chitchat | unknown */
    public static final String CATEGORY = "category";

    /** 选中的提示词模板内容 */
    public static final String SYSTEM_PROMPT = "systemPrompt";

    /** 选中的提示词模板ID */
    public static final String TEMPLATE_ID = "templateId";

    /** 选中的提示词模板名称 */
    public static final String TEMPLATE_NAME = "templateName";

    /** 知识库ID列表 */
    public static final String KB_IDS = "kbIds";

    /** 文件名称列表 */
    public static final String SOURCES = "sources";

    /** 有效知识库ID（用于查默认模板） */
    public static final String EFFECTIVE_KB_ID = "effectiveKbId";

    /** 会话ID（userId_sessionId） */
    public static final String CONVERSATION_ID = "conversationId";

    /** 检索得到的文档列表（经过Small-to-Big回表） */
    public static final String DOCUMENTS = "documents";

    /** 构建后的RAG上下文文本 */
    public static final String CONTEXT = "context";

    /** LLM生成的最终答案 */
    public static final String ANSWER = "answer";

    /** 步骤消息（当前节点描述，用于SSE步骤回显） */
    public static final String STEPS = "steps";

    /** SSE 步骤类型（thinking | tool | error），由各Node写入，Controller读取 */
    public static final String STEP_TYPE = "stepType";

    // ========== Phase 2（第二轮）预留字段 ==========

    /** 情绪检测结果：positive | neutral | negative */
    public static final String EMOTION = "emotion";

    /** 是否已触发转人工（true = 转接中） */
    public static final String ESCALATE = "escalate";

    /** MCP工具调用结果（Mock阶段为固定文案） */
    public static final String MCP_RESULT = "mcpResult";

    /** 累计Token使用（各Node自行累加，用于预算校验） */
    public static final String TOKEN_USAGE = "tokenUsage";

    /** 拆解后的子问题列表（QueryDecomposeNode 产出） */
    public static final String SUB_QUERIES = "subQueries";

    /**
     * 客户端取消标志（AtomicBoolean）
     * <p>由 GraphChatController 在 SSE doOnCancel 中置为 true，
     * 各节点在耗时操作前检查此标志，提前短路返回，避免客户端断开后继续消耗算力</p>
     */
    public static final String CANCELLED = "__cancelled__";

    /** SSE步骤类型常量 */
    public static final class StepType {
        /** 思考中（意图分类、生成中） */
        public static final String THINKING = "thinking";
        /** 工具调用中（检索、MCP、外部接口） */
        public static final String TOOL = "tool";
        /** 出错 */
        public static final String ERROR = "error";

        private StepType() {}
    }
}
