package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.McpUploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MCP 异步上传任务 Mapper
 *
 * @author Joseph
 */
@Mapper
public interface McpUploadTaskMapper extends BaseMapper<McpUploadTask> {

    @Select("SELECT * FROM mcp_upload_task WHERE task_id = #{taskId} LIMIT 1")
    McpUploadTask selectByTaskId(@Param("taskId") String taskId);
}
