package com.xushu.rag.graph.nodes;

import com.xushu.rag.entity.McpToolRegistry;
import com.xushu.rag.entity.PromptTemplate;
import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.mapper.McpToolRegistryMapper;
import com.xushu.rag.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 提示词路由Node
 * <p>根据意图类别 category 选择对应的提示词模板，注入 {context} 占位符</p>
 * <p>检索类意图追加工具推荐列表，LLM 自主判断是否推荐</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class PromptRouteNode {

    private final PromptTemplateService promptTemplateService;
    private final McpToolRegistryMapper mcpToolRegistryMapper;

    @Value("${mcp.tools.suggest.enabled:false}")
    private boolean mcpSuggestEnabled;

    /** 启用工具推荐的意图类别（闲聊类、转人工类不推荐） */
    private static final java.util.Set<String> SUGGEST_CATEGORIES =
            java.util.Set.of("calculation", "reference");

    public PromptRouteNode(PromptTemplateService promptTemplateService,
                           McpToolRegistryMapper mcpToolRegistryMapper) {
        this.promptTemplateService = promptTemplateService;
        this.mcpToolRegistryMapper = mcpToolRegistryMapper;
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

        // 工具智能推荐：检索类意图追加可用工具列表
        if (mcpSuggestEnabled && SUGGEST_CATEGORIES.contains(category)) {
            String toolHint = buildToolSuggestion(category);
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
     * 构建工具推荐提示（仅对检索类意图注入，limit 3，≤100 token）
     *
     * @param category 意图分类
     * @return 工具推荐文案，无可用工具时返回 null
     */
    private String buildToolSuggestion(String category) {
        List<McpToolRegistry> tools = mcpToolRegistryMapper.selectByToolCategory(category);
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\n\n【可用工具】");
        for (McpToolRegistry tool : tools) {
            String desc = tool.getDescription() != null && tool.getDescription().length() > 20
                    ? tool.getDescription().substring(0, 20) + "..."
                    : (tool.getDescription() != null ? tool.getDescription() : "");
            sb.append("\n- ").append(tool.getToolName()).append(": ").append(desc);
        }
        sb.append("\n如果对用户有帮助，请在回答中主动询问是否调用。");

        log.info("[PromptRoute] 注入工具推荐, category={}, tools={}", category,
                tools.stream().map(McpToolRegistry::getToolName).toList());
        return sb.toString();
    }
}
