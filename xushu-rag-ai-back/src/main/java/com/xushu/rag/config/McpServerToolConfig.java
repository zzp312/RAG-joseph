package com.xushu.rag.config;

import com.xushu.rag.tools.KnowledgeMcpTools;
import com.xushu.rag.tools.RagTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * @Tool 注解工具注册配置
 * <p>两个独立 Provider：</p>
 * <ul>
 *   <li>mcpToolCallbackProvider — KnowledgeMcpTools（给外部 Agent 用的 MCP Server 工具）</li>
 *   <li>internalToolCallbackProvider — RagTool 等（给页面聊天 tool_call 节点用的内部工具）</li>
 * </ul>
 * <p>两者互不干扰，内部 LLM 不会调用到外部 MCP 接口。</p>
 *
 * @author Joseph
 */
@Configuration
public class McpServerToolConfig {

    /** 外部 Agent 用的 MCP Server 工具（ask_knowledge / list_knowledge_bases 等） */
    @Bean
    @Lazy
    public ToolCallbackProvider mcpToolCallbackProvider(KnowledgeMcpTools knowledgeMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeMcpTools)
                .build();
    }

    /** 页面聊天 tool_call 节点用的内部工具（RagTool 等），未来新增内部 @Tool 在此追加 */
    @Bean
    @Lazy
    public ToolCallbackProvider internalToolCallbackProvider(RagTool ragTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ragTool)
                .build();
    }
}
