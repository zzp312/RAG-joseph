package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.McpServerConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MCP服务器配置 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface McpServerConfigMapper extends BaseMapper<McpServerConfig> {

    @Select("SELECT * FROM mcp_server_config WHERE enabled = 1 AND disabled = 0 ORDER BY create_time DESC")
    List<McpServerConfig> selectEnabled();

    @Select("SELECT * FROM mcp_server_config WHERE server_name = #{serverName} AND enabled = 1 AND disabled = 0")
    McpServerConfig selectByServerName(@Param("serverName") String serverName);

    @Select("SELECT * FROM mcp_server_config WHERE server_category = #{category} AND enabled = 1 AND disabled = 0 LIMIT 3")
    List<McpServerConfig> selectByCategory(@Param("category") String category);

    @Select("SELECT * FROM mcp_server_config WHERE enabled = 1 AND disabled = 0 ORDER BY create_time DESC")
    List<McpServerConfig> selectAllEnabled();
}
