package com.xushu.rag.controller;

import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.entity.McpServerConfig;
import com.xushu.rag.mapper.McpServerConfigMapper;
import com.xushu.rag.service.McpClientManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * MCP 接入层控制器
 * <p>提供MCP服务的查询和调用接口</p>
 *
 * @author Joseph
 */
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/mcp")
@RequiredArgsConstructor
public class McpGatewayController {

    private final McpServerConfigMapper configMapper;
    private final McpClientManager mcpClientManager;

    @Value("${mcp.gateway.secret-key:default-mcp-key}")
    private String secretKey;

    /**
     * 获取已启用的MCP服务列表
     */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> listTools() {
        List<McpServerConfig> configs = configMapper.selectEnabled();
        List<Map<String, Object>> result = configs.stream()
                .map(c -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", c.getId());
                    item.put("serverName", c.getServerName());
                    item.put("description", c.getDescription());
                    item.put("serverCategory", c.getServerCategory());
                    item.put("disabled", c.getDisabled());
                    return item;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定服务的工具列表（从MCP Server动态获取）
     */
    @GetMapping(value = "/tools/{serverName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listServerTools(@PathVariable String serverName) {
        List<io.modelcontextprotocol.spec.McpSchema.Tool> tools = mcpClientManager.getTools(serverName);
        Map<String, Object> result = new HashMap<>();
        result.put("serverName", serverName);
        result.put("tools", tools.stream()
                .map(t -> Map.of("name", t.name(), "description",
                        t.description() != null ? t.description() : ""))
                .toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 直接调用MCP工具（需secretKey鉴权）
     */
    @PostMapping(value = "/tool-call", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> toolCall(
            @RequestHeader(value = "X-MCP-Secret-Key", required = false) String reqSecretKey,
            @RequestBody Map<String, Object> request) {

        // secretKey 鉴权
        if (reqSecretKey == null || !secretKey.equals(reqSecretKey)) {
            log.warn("[MCP] secretKey鉴权失败: {}", reqSecretKey);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized: invalid secret key"));
        }

        String serverName = (String) request.getOrDefault("serverName", "");
        String toolName = (String) request.getOrDefault("toolName", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) request.getOrDefault("arguments", Map.of());

        log.info("[MCP] 工具调用: serverName={}, toolName={}, args={}", serverName, toolName, arguments);

        try {
            io.modelcontextprotocol.client.McpSyncClient client =
                    mcpClientManager.getOrCreateClient(serverName);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "服务 " + serverName + " 不可用"));
            }

            io.modelcontextprotocol.spec.McpSchema.CallToolResult result =
                    client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                            toolName, arguments != null ? arguments : Map.of()));

            StringBuilder text = new StringBuilder();
            if (result.content() != null) {
                for (Object content : result.content()) {
                    if (content instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {
                        text.append(tc.text());
                    } else {
                        text.append(content.toString());
                    }
                }
            }

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "serverName", serverName,
                    "toolName", toolName,
                    "result", text.toString()
            ));

        } catch (Exception e) {
            log.error("[MCP] 工具调用失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "调用失败: " + e.getMessage()));
        }
    }

    /**
     * 刷新工具缓存
     */
    @PostMapping("/refresh/{serverName}")
    public ResponseEntity<Map<String, String>> refreshTools(@PathVariable String serverName) {
        mcpClientManager.refreshTools(serverName);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "工具缓存已刷新"));
    }
}
