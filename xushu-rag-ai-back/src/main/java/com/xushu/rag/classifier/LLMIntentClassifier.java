package com.xushu.rag.classifier;

import com.xushu.rag.structured.IntentClassification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * L2 LLM 轻量意图分类（~200 token, 仅处理L1未命中的长尾请求）
 * <p>使用BeanOutputConverter实现结构化输出，要求LLM返回JSON后自动反序列化</p>
 * <p>fallback: 若结构化解析失败，回退到原有的trim+枚举关键词匹配</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class LLMIntentClassifier implements IntentClassifier {

    private final ChatClient chatClient;
    private final BeanOutputConverter<IntentClassification> converter;

    private static final String CLASSIFY_PROMPT = """
            判断用户意图、情绪、是否确认调用工具，以及目标MCP服务名，返回JSON格式：
            {"intent":"分类","reason":"理由简述","confidence":置信度,"emotion":"情绪","toolConfirm":是否确认,"targetMcpServer":"服务名"}

            意图分类选项：
            calculation - 计算类（工资、补偿、天数、费用等需要数值计算）
            reference  - 资料查阅类（定义、规定、流程、制度等知识查询）
            operation  - 操作类（入职、离职、请假、查询信息、合同签署、路线规划等需要调用外部工具的业务办理）
            escalation - 转人工（用户明确要求"转人工""找人工客服""叫人来"）
            chitchat   - 闲聊（问候、感谢、无关话题）

            情绪判断选项：
            positive - 积极、满意
            neutral  - 中性、正常
            negative - 负面、不满、不耐烦、愤怒

            是否确认调用工具（toolConfirm）：
            仅当intent=operation时需要判断，其他情况固定false。
            true  = 用户明确确认要执行操作
                   特别注意：必须结合对话历史判断，例如：
                   - 对话历史中助手推荐过调用工具，用户当前消息是"好的""嗯""可以""OK"等简短确认 → true
                   - 用户说"帮我查""执行""确认""查一下""我要看"等明确执行意图 → true
            false = 用户只是在询问或描述需求，尚未确认执行

            目标MCP服务名（targetMcpServer）：
            仅当intent=operation且能明确判断用户要调用哪个MCP服务时填写具体服务名，否则为空字符串""。

            示例：用户说"帮我查一下去公司的路线" → targetMcpServer="amap-maps"
                 用户说"查一下我的考勤" → targetMcpServer="ziniu-local-server"
                 用户说"能做什么"或无法判断 → targetMcpServer=""
                 以此类推

            置信度: 0.0~1.0（对分类判断的确信程度）
            """;

    public LLMIntentClassifier(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(CLASSIFY_PROMPT)
                .build();
        this.converter = new BeanOutputConverter<>(IntentClassification.class);
    }

    @Override
    public ClassifyResult classify(String question) {
        return classify(question, null);
    }

    @Override
    public ClassifyResult classify(String question, String history) {
        try {
            String format = converter.getFormat();
            String userPrompt = buildUserPrompt(question, history, format);

            String result = chatClient.prompt()
                    .user(userPrompt)
                    .options(ChatOptions.builder()
                            .maxTokens(150)
                            .build())
                    .call()
                    .content();

            if (result == null || result.trim().isEmpty()) {
                return new ClassifyResult(Category.UNKNOWN, "L2", 200);
            }

            // 【主路径】BeanOutputConverter结构化解析
            try {
                IntentClassification intentResult = converter.convert(result);
                Category category = Category.fromValue(intentResult.intent());
                String emotion = intentResult.emotion() != null ? intentResult.emotion() : "neutral";
                boolean toolConfirm = Boolean.TRUE.equals(intentResult.toolConfirm());
                String targetMcpServer = intentResult.targetMcpServer() != null ? intentResult.targetMcpServer() : "";
                log.info("[L2 LLM] 结构化分类完成 category={}, confidence={}, reason={}, emotion={}, toolConfirm={}, targetMcpServer={}",
                        category, intentResult.confidence(), intentResult.reason(), emotion, toolConfirm, targetMcpServer);
                return new ClassifyResult(category, "L2", (int) (intentResult.confidence() * 100), emotion, toolConfirm, targetMcpServer);
            } catch (Exception parseEx) {
                // 【fallback】结构化解析失败，回退原有逻辑
                log.warn("[L2 LLM] BeanOutputConverter解析失败，回退字符串匹配: {}", parseEx.getMessage());
                String trimmed = result.trim().toLowerCase();
                Category category = Category.fromValue(trimmed);
                log.info("[L2 LLM] fallback分类完成 category={}, raw={}", category, trimmed);
                return new ClassifyResult(category, "L2", 200);
            }

        } catch (Exception e) {
            log.warn("[L2 LLM] 分类失败, 默认unknown: {}", e.getMessage());
            return new ClassifyResult(Category.UNKNOWN, "L2", 200);
        }
    }

    /**
     * 构建 user prompt（包含历史对话）
     */
    private String buildUserPrompt(String question, String history, String format) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.trim().isEmpty()) {
            sb.append("对话历史：\n").append(history).append("\n\n");
        }
        sb.append("当前用户消息：").append(question).append("\n").append(format);
        return sb.toString();
    }
}
