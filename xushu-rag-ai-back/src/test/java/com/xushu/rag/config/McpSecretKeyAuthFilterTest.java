package com.xushu.rag.config;

import com.xushu.rag.entity.McpSecretKey;
import com.xushu.rag.mapper.McpSecretKeyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * McpSecretKeyAuthFilter 单元测试
 * <p>验证 MCP Server 的 SecretKey 鉴权行为：有效 key 放行，无效/缺失/INACTIVE key 返回 401。</p>
 *
 * @author Joseph
 */
@ExtendWith(MockitoExtension.class)
class McpSecretKeyAuthFilterTest {

    @Mock
    private McpSecretKeyMapper mcpSecretKeyMapper;

    @InjectMocks
    private McpSecretKeyAuthFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @Test
    void doFilter_withValidActiveKey_shouldContinueChain() throws ServletException, IOException {
        request.setServletPath("/mcp");
        request.addHeader("X-MCP-Secret-Key", "ziniu-mcp-dev-key-2026");
        when(mcpSecretKeyMapper.selectActiveBySecretKey("ziniu-mcp-dev-key-2026"))
                .thenReturn(McpSecretKey.builder().id(1L).secretKey("ziniu-mcp-dev-key-2026")
                        .status("ACTIVE").build());

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "Filter chain should continue");
    }

    @Test
    void doFilter_withMissingKeyHeader_shouldReturn401() throws ServletException, IOException {
        request.setServletPath("/mcp");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "Filter chain should NOT continue");
        assertTrue(response.getContentAsString().contains("X-MCP-Secret-Key"));
    }

    @Test
    void doFilter_withInvalidKey_shouldReturn401() throws ServletException, IOException {
        request.setServletPath("/mcp");
        request.addHeader("X-MCP-Secret-Key", "invalid-key");
        when(mcpSecretKeyMapper.selectActiveBySecretKey("invalid-key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "Filter chain should NOT continue");
    }

    @Test
    void doFilter_withNonMcpPath_shouldNotFilter() throws ServletException, IOException {
        request.setServletPath("/api/v1/knowledge/list");

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "Non-MCP path should pass through without auth check");
        verifyNoInteractions(mcpSecretKeyMapper);
    }
}
