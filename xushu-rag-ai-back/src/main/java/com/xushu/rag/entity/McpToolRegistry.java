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
 * MCP工具注册表实体
 * <p>存储MCP工具的业务属性（名称、描述、风险等级、启用状态），
 * 连接参数由 mcp-servers.json 管理</p>
 *
 * @author Joseph
 */
@TableName(value = "mcp_tool_registry")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpToolRegistry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具名称（如：员工信息查询、发送企微通知） */
    private String toolName;

    /** 工具描述（AI根据描述判断何时调用，20字以内） */
    private String description;

    /** 工具分类：calculation/reference/operation/default，用于检索后匹配推荐 */
    private String toolCategory;

    /** MCP Server端点URL（真实对接时填写） */
    private String endpoint;

    /** 风险等级：LOW/MEDIUM/HIGH */
    private String riskLevel;

    /** 是否启用：0-禁用，1-启用 */
    private Integer enabled;

    private Date createTime;

    private Date updateTime;
}
