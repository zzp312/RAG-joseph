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
 * MCP Server 鉴权密钥表实体
 * <p>支持多 key 共存以实现无感轮换，通过请求头 X-MCP-Secret-Key 传递。</p>
 *
 * @author Joseph
 */
@TableName(value = "mcp_secret_key")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpSecretKey {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String secretKey;

    private String name;

    private String status;

    private Date createTime;

    private Date updateTime;
}
