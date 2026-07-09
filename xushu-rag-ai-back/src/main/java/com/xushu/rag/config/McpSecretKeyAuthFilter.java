package com.xushu.rag.config;

import com.alibaba.fastjson.JSON;
import com.xushu.rag.entity.McpSecretKey;
import com.xushu.rag.mapper.McpSecretKeyMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP Server SecretKey 鉴权 Filter
 * <p>拦截 /mcp/** 请求，校验请求头 X-MCP-Secret-Key 是否有效（mcp_secret_key 表中 status=ACTIVE）。
 * 无效或缺失返回 401 Unauthorized。非 /mcp 路径直接放行，不影响业务接口。</p>
 *
 * <p>多 key 共存：支持多个 ACTIVE 状态的 secretKey 同时有效，实现无感轮换。
 * 旧 key 设为 INACTIVE 后立即失效。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class McpSecretKeyAuthFilter extends OncePerRequestFilter {

    private static final String SECRET_KEY_HEADER = "X-MCP-Secret-Key";
    private static final String MCP_PATH_PREFIX = "/mcp";

    @Autowired
    private McpSecretKeyMapper mcpSecretKeyMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path == null || !path.startsWith(MCP_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String secretKey = request.getHeader(SECRET_KEY_HEADER);

        if (secretKey == null || secretKey.isEmpty()) {
            writeUnauthorized(response, "Missing X-MCP-Secret-Key header");
            return;
        }

        McpSecretKey keyEntity = mcpSecretKeyMapper.selectActiveBySecretKey(secretKey);
        if (keyEntity == null) {
            writeUnauthorized(response, "Invalid or inactive X-MCP-Secret-Key");
            return;
        }

        // 鉴权通过，将 callerSecretKeyId 存入请求属性，供审计日志使用
        request.setAttribute("callerSecretKeyId", keyEntity.getId());

        filterChain.doFilter(request, response);
    }

    /**
     * 写入 401 Unauthorized 响应
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> error = new HashMap<>();
        error.put("code", 401);
        error.put("message", message);

        response.getWriter().write(JSON.toJSONString(error));
        log.warn("[McpAuth] 鉴权失败: {}", message);
    }
}
