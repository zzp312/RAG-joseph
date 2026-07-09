package com.xushu.rag.pojo.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 文件上传结果 DTO
 * <p>KnowledgeUploadService 和 MCP upload_kb_file 工具共用的返回结构。</p>
 *
 * @author Joseph
 */
@Data
@Builder
public class UploadResult {
    private String fileName;
    private Long kbId;
    private String kbName;
    private String version;
    private String status;
    private int chunkCount;
    private String errorMessage;
}
