package com.xushu.rag.mcp;

import com.xushu.rag.common.BaseResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具错误响应统一构建工具
 * <p>统一 MCP 工具的错误返回格式：{isError: true, code, message}。
 * 正常返回由工具自行构建业务 Map，错误返回统一走本类。</p>
 *
 * <p>错误码约定：</p>
 * <ul>
 *   <li>40000 - 参数缺失或非法</li>
 *   <li>40001 - 文件超限</li>
 *   <li>40003 - 业务约束冲突（如 auto_classify=false 但未提供 kb_name）</li>
 *   <li>40401 - 资源不存在</li>
 *   <li>50000 - 内部服务器错误（兜底）</li>
 * </ul>
 *
 * @author Joseph
 */
public final class McpErrorResponseHelper {

    public static final int CODE_SUCCESS = 0;
    public static final int CODE_INTERNAL_ERROR = 50000;

    private McpErrorResponseHelper() {
    }

    /**
     * 构建错误返回
     *
     * @param code    业务错误码
     * @param message 错误信息（英文，便于 Agent 解析）
     * @return {isError: true, code, message}
     */
    public static Map<String, Object> buildError(int code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("isError", true);
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    /**
     * 构建内部服务器错误兜底返回
     *
     * @return {isError: true, code: 50000, message: "internal server error"}
     */
    public static Map<String, Object> buildInternalError() {
        return buildError(CODE_INTERNAL_ERROR, "internal server error");
    }

    /**
     * 将业务层 BaseResponse 转换为 MCP tool result 格式
     * <p>code=0 → isError=false, content=data；
     * code!=0 → isError=true, content={code, message}</p>
     *
     * @param response 业务层返回
     * @return MCP 格式 Map
     */
    public static Map<String, Object> fromBaseResponse(BaseResponse<?> response) {
        if (response == null) {
            return buildInternalError();
        }
        if (response.getCode() == CODE_SUCCESS) {
            Map<String, Object> result = new HashMap<>();
            result.put("isError", false);
            result.put("data", response.getData());
            return result;
        }
        return buildError(response.getCode(), response.getMessage());
    }
}
