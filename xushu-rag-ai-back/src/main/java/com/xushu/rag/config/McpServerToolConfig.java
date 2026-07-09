package com.xushu.rag.config;

import com.xushu.rag.tools.KnowledgeMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 将 @Tool 注解的 Bean 显式注册为 MCP Server 工具
 * <p>KnowledgeMcpTools 已对 KnowledgeUploadService 使用 @Lazy 注入，
 * 打破了 ToolCallbackProvider → ChatModel 的循环依赖。</p>
 *
 * @author Joseph
 */
@Configuration
public class McpServerToolConfig {

    @Bean
    @Lazy
    public ToolCallbackProvider mcpToolCallbackProvider(KnowledgeMcpTools knowledgeMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeMcpTools)
                .build();
    }
}
