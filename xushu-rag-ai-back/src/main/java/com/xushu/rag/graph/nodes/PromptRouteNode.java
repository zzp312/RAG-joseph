package com.xushu.rag.graph.nodes;

import com.xushu.rag.entity.McpServerConfig;
import com.xushu.rag.entity.PromptTemplate;
import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.mapper.McpServerConfigMapper;
import com.xushu.rag.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 提示词路由Node
 * <p>根据意图类别 category 选择对应的提示词模板，注入 {context} 占位符</p>
 * <p>检索类意图追加MCP服务推荐列表，LLM 自主判断是否推荐</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class PromptRouteNode {

    private final PromptTemplateService promptTemplateService;
    private final McpServerConfigMapper mcpServerConfigMapper;

    @Value("${mcp.tools.suggest.enabled:true}")
    private boolean mcpSuggestEnabled;

    /**
     * 不推荐工具的意图类别（转人工直接转人工，无需推荐）
     * 其他所有类别都尝试注入工具推荐，由 LLM 判断是否相关
     */
    private static final java.util.Set<String> SKIP_SUGGEST_CATEGORIES =
            java.util.Set.of("escalation");

    public PromptRouteNode(PromptTemplateService promptTemplateService,
                           McpServerConfigMapper mcpServerConfigMapper) {
        this.promptTemplateService = promptTemplateService;
        this.mcpServerConfigMapper = mcpServerConfigMapper;
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        String category = (String) state.getOrDefault(StateKeys.CATEGORY, "unknown");
        Long effectiveKbId = (Long) state.getOrDefault(StateKeys.EFFECTIVE_KB_ID, null);

        // 根据 category 和 knowledge_base 查模板
        PromptTemplate template = promptTemplateService.findByCategoryAndKbId(category, effectiveKbId);

        String systemPrompt;
        Long templateId;
        String templateName;

        if (template != null && template.getTemplateContent() != null) {
            systemPrompt = template.getTemplateContent();
            templateId = template.getId();
            templateName = template.getName();
            log.info("[PromptRoute] category={}, 命中模板: {} (id={})", category, templateName, templateId);
        } else {
            systemPrompt = "你是Joseph.zhou知识库系统的对话助手，请严格基于知识库内容回答用户问题。\n{context}";
            templateId = -1L;
            templateName = "默认模板";
            log.info("[PromptRoute] category={}, 使用默认模板", category);
        }

        // RAG 规范附录：仅对检索类意图追加（operation/chitchat/escalation 不需要）
        if (!"operation".equals(category) && !"chitchat".equals(category) && !"escalation".equals(category)) {
            String appendix = "\n\n【回答规范】\n" +
                    "1. 图片：按指令输出Markdown图片语法 ![描述](URL)。\n" +
                    "2. 来源引用：仅当你引用了上下文中的具体信息时，才在末尾列出\"📚 参考来源\"，格式：`- 文件名 (版本xxx)`。如果回答是\"知识库中暂无相关内容\"，不要列出任何来源。\n" +
                    "3. 诚实：仅基于上下文回答，不确定时说\"知识库中暂无相关内容\"，不编造。";
            systemPrompt = systemPrompt + appendix;
        }

        // 工具智能推荐：全量查询启用工具，由 LLM 判断是否推荐
        if (mcpSuggestEnabled && !SKIP_SUGGEST_CATEGORIES.contains(category)) {
            String toolHint = buildToolSuggestion();
            if (toolHint != null) {
                systemPrompt = systemPrompt + toolHint;
            }
        }

        return Map.of(
                StateKeys.SYSTEM_PROMPT, systemPrompt,
                StateKeys.TEMPLATE_ID, templateId,
                StateKeys.TEMPLATE_NAME, templateName,
                StateKeys.STEPS, "提示词路由: " + templateName
        );
    }

    /**
     * 构建工具推荐提示（全量查询启用工具，由 LLM 判断是否推荐）
     * <p>提示词中要求 LLM 明确告知入参，提升用户体验</p>
     *
     * @return 工具推荐文案，无可用服务时返回 null
     */
    private String buildToolSuggestion() {
        List<McpServerConfig> configs = mcpServerConfigMapper.selectAllEnabled();
        if (configs == null || configs.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\n\n【可用服务】");
        for (McpServerConfig config : configs) {
            String desc = config.getDescription() != null && config.getDescription().length() > 50
                    ? config.getDescription().substring(0, 50) + "..."
                    : (config.getDescription() != null ? config.getDescription() : "");
            sb.append("\n- ").append(config.getServerName()).append(": ").append(desc);
        }
        sb.append("\n\n请根据用户问题判断是否需要推荐上述服务。如果需要推荐，请按以下格式回复：");
        sb.append("\n1. 明确告知用户：将会调用哪个服务（说名字）");
        sb.append("\n2. 告知用户：该服务需要哪些参数（必填项必须列出）");
        sb.append("\n3. 询问用户：是否确认执行，以及是否需要补充参数");
        sb.append("\n\n示例：");
        sb.append("\n「我可以帮您调用高德地图查询路线。需要您提供：起点位置、终点位置。请问起点和终点分别是？」");
        sb.append("\n\n注意：");
        sb.append("\n- 仅推荐与用户需求相关的服务，不相关的不要提及");
        sb.append("\n- 如果用户消息中已包含部分参数，请告知用户还缺什么");
        sb.append("\n- 不要在本次回复中直接执行工具，等用户确认后再执行");

        log.info("[PromptRoute] 注入服务推荐, services={}",
                configs.stream().map(McpServerConfig::getServerName).toList());
        return sb.toString();
    }
}
