package com.xushu.rag.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xushu.rag.entity.McpServerConfig;
import com.xushu.rag.mapper.McpServerConfigMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 管理器
 * <p>从 mcp_server_config 表读取配置，懒加载创建 McpSyncClient，
 * 管理 stdio 子进程和 HTTP/Sse 连接的生命周期</p>
 *
 * @author Joseph
 */
@Slf4j
@Service
public class McpClientManager {

    private final McpServerConfigMapper configMapper;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<McpSchema.Tool>> clientTools = new ConcurrentHashMap<>();

    public McpClientManager(McpServerConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    /**
     * 获取所有启用的MCP Server配置
     */
    public List<McpServerConfig> getEnabledConfigs() {
        return configMapper.selectEnabled();
    }

    /**
     * 按分类获取启用的配置
     */
    public List<McpServerConfig> getConfigsByCategory(String category) {
        return configMapper.selectByCategory(category);
    }

    /**
     * 获取或创建 McpSyncClient（懒加载）
     */
    public McpSyncClient getOrCreateClient(String serverName) {
        return clients.computeIfAbsent(serverName, name -> {
            McpServerConfig config = configMapper.selectByServerName(name);
            if (config == null) {
                log.warn("[McpClientManager] 配置不存在: serverName={}", name);
                return null;
            }
            return buildAndInitClient(config);
        });
    }

    /**
     * 获取所有已创建的 McpSyncClient
     */
    public List<McpSyncClient> getAllClients() {
        List<McpServerConfig> configs = configMapper.selectEnabled();
        List<McpSyncClient> result = new ArrayList<>();
        for (McpServerConfig config : configs) {
            McpSyncClient client = getOrCreateClient(config.getServerName());
            if (client != null) {
                result.add(client);
            }
        }
        return result;
    }

    /**
     * 获取指定Server暴露的工具列表（缓存）
     */
    public List<McpSchema.Tool> getTools(String serverName) {
        McpSyncClient client = getOrCreateClient(serverName);
        if (client == null) return List.of();
        return clientTools.computeIfAbsent(serverName, k -> {
            try {
                return client.listTools().tools();
            } catch (Exception e) {
                log.error("[McpClientManager] 获取工具列表失败: serverName={}, err={}", serverName, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * 获取所有Server暴露的全部工具
     */
    public Map<String, List<McpSchema.Tool>> getAllTools() {
        Map<String, List<McpSchema.Tool>> result = new LinkedHashMap<>();
        for (McpServerConfig config : getEnabledConfigs()) {
            List<McpSchema.Tool> tools = getTools(config.getServerName());
            if (!tools.isEmpty()) {
                result.put(config.getServerName(), tools);
            }
        }
        return result;
    }

    /**
     * 刷新指定工具的缓存
     */
    public void refreshTools(String serverName) {
        clientTools.remove(serverName);
    }

    /**
     * 从配置构建并初始化 McpSyncClient
     */
    private McpSyncClient buildAndInitClient(McpServerConfig config) {
        try {
            JSONObject cfg = JSON.parseObject(config.getConfigJson());
            McpClientTransport transport = buildTransport(config.getServerName(), cfg);

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            client.initialize();

            log.info("[McpClientManager] MCP Client 初始化成功: serverName={}, transport={}",
                    config.getServerName(), cfg.containsKey("url") ? cfg.getString("transport") : "stdio");
            return client;
        } catch (Exception e) {
            log.error("[McpClientManager] MCP Client 初始化失败: serverName={}, err={}",
                    config.getServerName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据 config_json 构建传输层
     */
    @SuppressWarnings("resource")
    private McpClientTransport buildTransport(String serverName, JSONObject cfg) {
        if (cfg.containsKey("url")) {
            // HTTP 模式：根据 transport 字段选择传输类型，默认 SSE（兼容旧配置）
            String url = cfg.getString("url");
            String transport = cfg.getString("transport");
            if ("streamable".equalsIgnoreCase(transport)) {
                // Streamable HTTP 模式（POST，兼容新版本 MCP 服务）
                // mcp-core 0.15.0+ 已修复：不再初始化时尝试建立 SSE 长连接
                log.info("[McpClientManager] 创建StreamableHttp传输: serverName={}, url={}", serverName, url);
                return HttpClientStreamableHttpTransport.builder(url).build();
            } else {
                // 默认 SSE 模式（兼容只支持 SSE 的旧服务）
                log.info("[McpClientManager] 创建SSE传输: serverName={}, url={}", serverName, url);
                return HttpClientSseClientTransport.builder(url).build();
            }
        } else {
            // STDIO 模式
            String command = cfg.getString("command");
            JSONArray argsArr = cfg.getJSONArray("args");
            JSONObject envObj = cfg.getJSONObject("env");

            List<String> args = argsArr != null ? argsArr.toJavaList(String.class) : List.of();
            Map<String, String> env = new HashMap<>();
            if (envObj != null) {
                env = envObj.toJavaObject(Map.class);
            }

            log.info("[McpClientManager] 创建Stdio传输: serverName={}, command={}, args={}",
                    serverName, command, args);

            ServerParameters params = ServerParameters.builder(command)
                    .args(args.toArray(new String[0]))
                    .env(env)
                    .build();

            return new StdioClientTransport(params, McpJsonMapper.createDefault());
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[McpClientManager] 销毁所有MCP Client, count={}", clients.size());
        for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
                log.info("[McpClientManager] 已关闭: serverName={}", entry.getKey());
            } catch (Exception e) {
                log.warn("[McpClientManager] 关闭失败: serverName={}, err={}",
                        entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
        clientTools.clear();
    }
}
