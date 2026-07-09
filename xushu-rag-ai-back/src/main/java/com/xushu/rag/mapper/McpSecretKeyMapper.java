package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.McpSecretKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MCP 鉴权密钥 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface McpSecretKeyMapper extends BaseMapper<McpSecretKey> {

    @Select("SELECT * FROM mcp_secret_key WHERE secret_key = #{secretKey} AND status = 'ACTIVE' LIMIT 1")
    McpSecretKey selectActiveBySecretKey(@Param("secretKey") String secretKey);
}
