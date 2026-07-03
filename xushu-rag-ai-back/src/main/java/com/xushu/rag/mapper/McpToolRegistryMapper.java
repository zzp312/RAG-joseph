package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.McpToolRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MCP工具注册表 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface McpToolRegistryMapper extends BaseMapper<McpToolRegistry> {

    @Select("SELECT * FROM mcp_tool_registry WHERE enabled = 1 ORDER BY create_time DESC")
    List<McpToolRegistry> selectEnabledTools();

    @Select("SELECT * FROM mcp_tool_registry WHERE tool_name = #{toolName} AND enabled = 1")
    McpToolRegistry selectByName(@Param("toolName") String toolName);

    @Select("SELECT * FROM mcp_tool_registry WHERE tool_category = #{toolCategory} AND enabled = 1 LIMIT 3")
    List<McpToolRegistry> selectByToolCategory(@Param("toolCategory") String toolCategory);
}
