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
 * MCP 异步上传任务表实体
 * <p>记录上传任务的全生命周期：PENDING → PROCESSING → SUCCESS/FAILED，
 * 含阶段（PARSING/CHUNKING/EMBEDDING/IMAGE_PROCESSING）、进度、失败原因。</p>
 *
 * @author Joseph
 */
@TableName(value = "mcp_upload_task")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpUploadTask {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String fileName;

    private Long fileSize;

    private String fileUrl;

    private Long kbId;

    private String kbName;

    private String status;

    private String stage;

    private Integer progress;

    private String errorMessage;

    private String errorStage;

    private Long callerSecretKeyId;

    private String vectorIds;

    private Date createTime;

    private Date updateTime;

    private Date completeTime;
}
