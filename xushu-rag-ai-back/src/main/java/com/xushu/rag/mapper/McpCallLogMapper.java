package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.McpCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * MCP 调用审计日志 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface McpCallLogMapper extends BaseMapper<McpCallLog> {
}
