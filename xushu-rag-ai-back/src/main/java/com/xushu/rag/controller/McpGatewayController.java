package com.xushu.rag.controller;

import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.mapper.McpToolRegistryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 接入层控制器（Mock）
 * <p>提供MCP工具的查询和模拟调用接口。当前阶段返回mock响应，
 * 工具连接参数由 mcp-servers.json 管理</p>
 *
 * @author Joseph
 */
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/mcp")
@RequiredArgsConstructor
public class McpGatewayController {

    private final McpToolRegistryMapper mcpToolRegistryMapper;

    @Value("${mcp.gateway.secret-key:default-mcp-key}")
    private String secretKey;

    /**
     * 获取已启用的MCP工具列表
     */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> listTools() {
        List<Map<String, Object>> tools = mcpToolRegistryMapper.selectEnabledTools()
                .stream()
                .map(t -> {
                    Map<String, Object> tool = new HashMap<>();
                    tool.put("id", t.getId());
                    tool.put("toolName", t.getToolName());
                    tool.put("description", t.getDescription());
                    tool.put("riskLevel", t.getRiskLevel());
                    return tool;
                })
                .toList();
        return ResponseEntity.ok(tools.isEmpty() ? Collections.emptyList() : tools);
    }

    /**
     * Mock MCP工具调用
     * <p>当前阶段所有调用返回mock响应，真实对接时替换</p>
     */
    @PostMapping(value = "/tool-call", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> toolCall(
            @RequestHeader(value = "X-MCP-Secret-Key", required = false) String reqSecretKey,
            @RequestBody Map<String, Object> request) {

        // secretKey 鉴权
        if (reqSecretKey == null || !secretKey.equals(reqSecretKey)) {
            log.warn("[MCP] secretKey鉴权失败: {}", reqSecretKey);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized: invalid secret key"));
        }

        String toolName = (String) request.getOrDefault("toolName", "未知工具");
        log.info("[MCP Mock] 工具调用: toolName={}, params={}", toolName, request.getOrDefault("params", "{}"));

        return ResponseEntity.ok(Map.of(
                "status", "mock",
                "message", "MCP接口已就绪，待对接真实系统",
                "toolName", toolName
        ));
    }
}
