package com.xushu.rag.classifier;

import com.xushu.rag.entity.McpServerConfig;
import com.xushu.rag.mapper.McpServerConfigMapper;
import com.xushu.rag.structured.IntentClassification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * L2 LLM 轻量意图分类（~200 token, 仅处理L1未命中的长尾请求）
 * <p>使用BeanOutputConverter实现结构化输出，要求LLM返回JSON后自动反序列化</p>
 * <p>fallback: 若结构化解析失败，回退到原有的trim+枚举关键词匹配</p>
 * <p>分类提示词外置到 classpath:prompts/intent-classify.st，修改提示词无需改代码</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class LLMIntentClassifier implements IntentClassifier {

    private static final String CLASSIFY_PROMPT_PATH = "prompts/intent-classify.st";

    /** 文件缺失时的内联兜底提示词 */
    private static final String FALLBACK_CLASSIFY_PROMPT = """
            判断用户意图、情绪、是否确认调用工具，以及目标MCP服务名，返回JSON格式：
            {"intent":"分类","reason":"理由简述","confidence":置信度,"emotion":"情绪","toolConfirm":是否确认,"targetMcpServer":"服务名"}

            意图分类选项：
            calculation - 计算类（工资、补偿、天数、费用等需要数值计算）
            reference  - 资料查阅类（定义、规定、流程、制度等知识查询）
            operation  - 操作类（需要调用外部工具的业务办理，如路线规划、距离查询、考勤查询等）
                        重要判断：当用户问题涉及"多远/多久/路线/距离/时间"等需要实时数据的，分类为 operation
            escalation - 转人工
            chitchat   - 闲聊
            planning   - 规划类（旅游计划、方案设计、行程安排等需要基于素材推理整合的任务）
                        特征词：计划/方案/安排/清单/给我几个/对比一下/帮我选/规划
                        分类优先级：当planning特征词与operation隐式触发词（路程/时间/价格/距离）同时出现时，优先planning

            情绪判断：positive/neutral/negative
            toolConfirm：仅intent=operation时需要判断，其他固定false
            targetMcpServer：仅intent=operation时填写，必须从下方可用服务列表中选取，列表中不存在则填空

            {mcp_services_block}

            置信度: 0.0~1.0
            """;

    private final ChatClient chatClient;
    private final BeanOutputConverter<IntentClassification> converter;
    private final McpServerConfigMapper mcpServerConfigMapper;
    private final String classifySystemPrompt;

    public LLMIntentClassifier(ChatModel chatModel, McpServerConfigMapper mcpServerConfigMapper) {
        this.mcpServerConfigMapper = mcpServerConfigMapper;
        this.classifySystemPrompt = loadClassifyPrompt();
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(classifySystemPrompt)
                .build();
        this.converter = new BeanOutputConverter<>(IntentClassification.class);
    }

    /**
     * 从 classpath 加载分类提示词文件，缺失时降级到内联兜底
     * <p>文件位置：resources/prompts/intent-classify.st</p>
     */
    private String loadClassifyPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(CLASSIFY_PROMPT_PATH);
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            log.info("[LLMIntentClassifier] 分类提示词加载成功, 路径={}, 长度={}字", CLASSIFY_PROMPT_PATH, content.length());
            return content;
        } catch (IOException e) {
            log.warn("[LLMIntentClassifier] 分类提示词文件缺失，降级为内联兜底: {}", CLASSIFY_PROMPT_PATH);
            return FALLBACK_CLASSIFY_PROMPT;
        }
    }

    @Override
    public ClassifyResult classify(String question) {
        return classify(question, null);
    }

    @Override
    public ClassifyResult classify(String question, String history) {
        try {
            String format = converter.getFormat();
            // 动态注入可用MCP服务列表，让LLM知道真实服务名而非凭空编造
            String mcpServicesBlock = buildMcpServicesBlock();
            String userPrompt = buildUserPrompt(question, history, format, mcpServicesBlock);

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
                String answerType = intentResult.answerType() != null ? intentResult.answerType() : "description";
                log.info("[L2 LLM] 结构化分类完成 category={}, confidence={}, reason={}, emotion={}, toolConfirm={}, targetMcpServer={}, answerType={}",
                        category, intentResult.confidence(), intentResult.reason(), emotion, toolConfirm, targetMcpServer, answerType);
                return new ClassifyResult(category, "L2", (int) (intentResult.confidence() * 100), emotion, toolConfirm, targetMcpServer, answerType);
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
     * 动态构建可用MCP服务列表文本（注入到user prompt，让LLM知道真实服务名）
     */
    private String buildMcpServicesBlock() {
        try {
            List<McpServerConfig> configs = mcpServerConfigMapper.selectAllEnabled();
            if (configs == null || configs.isEmpty()) {
                return "当前无可用MCP服务，targetMcpServer 一律填空字符串 \"\"。";
            }
            StringBuilder sb = new StringBuilder("【可用MCP服务列表（targetMcpServer 必须从以下服务名中选取，禁止编造）】\n");
            for (McpServerConfig c : configs) {
                String desc = c.getDescription() != null && !c.getDescription().isEmpty()
                        ? " — " + c.getDescription() : "";
                sb.append("- ").append(c.getServerName()).append(desc).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[LLMIntentClassifier] 查询MCP服务列表失败: {}", e.getMessage());
            return "MCP服务列表暂不可用，targetMcpServer 一律填空字符串 \"\"。";
        }
    }

    /**
     * 构建 user prompt（包含历史对话 + 可用服务列表）
     */
    private String buildUserPrompt(String question, String history, String format, String mcpServicesBlock) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.trim().isEmpty()) {
            sb.append("对话历史：\n").append(history).append("\n\n");
        }
        sb.append("当前用户消息：").append(question).append("\n");
        // 注入可用服务列表（作为分类参考，不是主提示词），放在format之前以减小对JSON输出的干扰
        sb.append(mcpServicesBlock).append("\n");
        sb.append(format);
        return sb.toString();
    }
}
