package com.xushu.rag.graph.nodes;

import com.xushu.rag.entity.PromptTemplate;
import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提示词路由Node
 * <p>根据意图类别 category 选择对应的提示词模板，注入 {context} 占位符</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class PromptRouteNode {

    private final PromptTemplateService promptTemplateService;

    public PromptRouteNode(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
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

        // 统一追加 图片输出规范 + 来源引用规范
        String appendix = "\n\n【回答规范】\n" +
                "1. 图片：按指令输出Markdown图片语法 ![描述](URL)。\n" +
                "2. 来源引用：仅当你引用了上下文中的具体信息时，才在末尾列出\"📚 参考来源\"，格式：`- 文件名 (版本xxx)`。如果回答是\"知识库中暂无相关内容\"，不要列出任何来源。\n" +
                "3. 诚实：仅基于上下文回答，不确定时说\"知识库中暂无相关内容\"，不编造。";
        systemPrompt = systemPrompt + appendix;

        return Map.of(
                StateKeys.SYSTEM_PROMPT, systemPrompt,
                StateKeys.TEMPLATE_ID, templateId,
                StateKeys.TEMPLATE_NAME, templateName
        );
    }
}
