package com.xushu.rag.mcp;

import com.xushu.rag.common.BaseResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpErrorResponseHelper 单元测试
 *
 * @author Joseph
 */
class McpErrorResponseHelperTest {

    @Test
    void buildError_shouldReturnIsErrorTrueWithCodeAndMessage() {
        Map<String, Object> result = McpErrorResponseHelper.buildError(40000, "missing param");

        assertEquals(true, result.get("isError"));
        assertEquals(40000, result.get("code"));
        assertEquals("missing param", result.get("message"));
        assertEquals(3, result.size());
    }

    @Test
    void buildInternalError_shouldReturnCode50000() {
        Map<String, Object> result = McpErrorResponseHelper.buildInternalError();

        assertEquals(true, result.get("isError"));
        assertEquals(50000, result.get("code"));
        assertEquals("internal server error", result.get("message"));
    }

    @Test
    void fromBaseResponse_withSuccessCode_shouldReturnIsErrorFalseAndData() {
        BaseResponse<String> response = new BaseResponse<>(0, "ok data", "success");

        Map<String, Object> result = McpErrorResponseHelper.fromBaseResponse(response);

        assertEquals(false, result.get("isError"));
        assertEquals("ok data", result.get("data"));
    }

    @Test
    void fromBaseResponse_withErrorCode_shouldReturnIsErrorTrueWithCodeAndMessage() {
        BaseResponse<Object> response = new BaseResponse<>(40401, null, "not found");

        Map<String, Object> result = McpErrorResponseHelper.fromBaseResponse(response);

        assertEquals(true, result.get("isError"));
        assertEquals(40401, result.get("code"));
        assertEquals("not found", result.get("message"));
        assertNull(result.get("data"));
    }

    @Test
    void fromBaseResponse_withNull_shouldReturnInternalError() {
        Map<String, Object> result = McpErrorResponseHelper.fromBaseResponse(null);

        assertEquals(true, result.get("isError"));
        assertEquals(50000, result.get("code"));
    }
}
