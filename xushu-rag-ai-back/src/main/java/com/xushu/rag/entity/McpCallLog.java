package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * MCP 调用审计日志表实体
 * <p>记录所有 MCP 工具调用，用于运维监控和问题排查。
 * arguments 和 result 字段截断到 2000 字符。</p>
 *
 * @author Joseph
 */
@TableName(value = "mcp_call_log")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpCallLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String toolName;

    private Long callerSecretKeyId;

    private String arguments;

    private String result;

    private Long durationMs;

    private String status;

    private String errorMessage;

    private Date createTime;
}
