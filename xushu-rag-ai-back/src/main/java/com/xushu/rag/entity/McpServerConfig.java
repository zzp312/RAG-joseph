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
 * MCP服务器配置实体
 * <p>存储MCP Server的连接配置（JSON格式），工具由MCP Server自动暴露，无需手动注册</p>
 *
 * @author Joseph
 */
@TableName(value = "mcp_server_config")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpServerConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务名称（如：amap-maps、ziniu-local-server） */
    private String serverName;

    /** 描述（用于提示词工具推荐） */
    private String description;

    /** 服务分类：reference/operation/calculation */
    private String serverCategory;

    /** MCP连接配置JSON（stdio: command+args+env / http: url+type） */
    private String configJson;

    /** 是否禁用：0-启用，1-禁用 */
    private Integer disabled;

    /** 是否启用：0-禁用，1-启用 */
    private Integer enabled;

    private Date createTime;

    private Date updateTime;
}
