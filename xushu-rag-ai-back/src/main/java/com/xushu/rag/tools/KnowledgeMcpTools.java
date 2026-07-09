package com.xushu.rag.tools;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.xushu.rag.entity.KnowledgeBase;
import com.xushu.rag.entity.McpUploadTask;
import com.xushu.rag.mcp.McpAuditLogger;
import com.xushu.rag.mcp.McpErrorResponseHelper;
import com.xushu.rag.mapper.DocumentMapper;
import com.xushu.rag.mapper.KnowledgeBaseMapper;
import com.xushu.rag.mapper.McpUploadTaskMapper;
import com.xushu.rag.pojo.dto.UploadResult;
import com.xushu.rag.service.HybridSearchService;
import com.xushu.rag.service.KnowledgeUploadService;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.xushu.rag.graph.RagGraphAgent;
import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 知识库 MCP 工具集
 * <p>通过 Spring AI MCP Server 暴露 4 个工具：list_knowledge_bases / ask_knowledge /
 * get_upload_status / upload_kb_file，供外部 Agent 调用。</p>
 * <p>upload_kb_file 仅支持 file_url 方式（HTTP/HTTPS），大文件请先上传到OSS/NAS获取URL后再调用。</p>
 *
 * <p>工具描述采用中英双语，便于国内外 Agent 识别。
 * 每个工具调用通过 withAudit 统一记录审计日志 + 异常兜底。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class KnowledgeMcpTools {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_URL_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int SYNC_THRESHOLD = 20 * 1024 * 1024; // 20MB，超过则异步
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private McpUploadTaskMapper mcpUploadTaskMapper;

    @Autowired
    @Lazy
    private KnowledgeUploadService knowledgeUploadService;

    @Autowired
    @Lazy
    private RagGraphAgent ragGraphAgent;

    @Autowired
    private McpAuditLogger mcpAuditLogger;

    @Autowired
    @Qualifier("mcpUploadExecutor")
    private ThreadPoolExecutor mcpUploadExecutor;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 列出所有 ACTIVE 状态的知识库（支持分页）
     * <p>列出所有启用的知识库，含每个知识库的文档数量 | List all active knowledge bases with document count.</p>
     *
     * @param page     页码（从1开始，默认1）| Page number (1-based, default 1)
     * @param pageSize 每页条数（默认20）| Page size (default 20)
     * @return {kbs: [{kbId, kbName, docCount, description, createTime}], total, page, pageSize}
     */
    @Tool(name = "list_knowledge_bases",
            description = "列出所有启用的知识库（含文档数量）| List all active knowledge bases with document count")
    public Map<String, Object> listKnowledgeBases(
            @ToolParam(description = "页码，从1开始，默认1 | Page number, 1-based, default 1", required = false)
            Integer page,
            @ToolParam(description = "每页条数，默认20 | Page size, default 20", required = false)
            Integer pageSize) {
        Map<String, Object> args = new HashMap<>();
        args.put("page", page);
        args.put("pageSize", pageSize);
        return withAudit("list_knowledge_bases", args, () -> doListKnowledgeBases(page, pageSize));
    }

    private Map<String, Object> doListKnowledgeBases(Integer page, Integer pageSize) {
        int targetPage = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int targetPageSize = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : pageSize;

        List<KnowledgeBase> allActive = knowledgeBaseMapper.selectActiveKnowledgeBases();
        int total = allActive.size();

        int fromIndex = (targetPage - 1) * targetPageSize;
        int toIndex = Math.min(fromIndex + targetPageSize, total);
        List<KnowledgeBase> paged = fromIndex >= total
                ? new ArrayList<>()
                : allActive.subList(fromIndex, toIndex);

        List<Map<String, Object>> kbs = paged.stream().map(kb -> {
            Map<String, Object> item = new HashMap<>();
            item.put("kbId", kb.getId());
            item.put("kbName", kb.getName());
            item.put("docCount", documentMapper.countByKbId(kb.getId()));
            item.put("description", kb.getDescription());
            item.put("createTime", kb.getCreateTime());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("kbs", kbs);
        result.put("total", total);
        result.put("page", targetPage);
        result.put("pageSize", targetPageSize);
        return result;
    }

    /**
     * 知识库问答（全流程 Graph Agent）
     * <p>经过意图分类→提示词路由→混合检索→上下文构建→LLM生成，返回自然语言回答 | Full RAG pipeline: intent classify → prompt route → hybrid retrieval → context build → LLM generate.</p>
     *
     * @param question 用户问题（必填）| Question (required)
     * @param kbId     知识库ID（可选，限定检索范围）| Knowledge base ID (optional)
     * @return {answer, steps, category, kbId}
     */
    @Tool(name = "ask_knowledge",
            description = "知识库问答，经过意图分类→检索→LLM生成，返回自然语言回答 | Full RAG Q&A with intent classify → retrieval → LLM generation")
    public Map<String, Object> askKnowledge(
            @ToolParam(description = "用户问题 | Question", required = true) String question,
            @ToolParam(description = "知识库ID（可选，限定检索范围）| Knowledge base ID (optional)", required = false)
            Long kbId) {
        Map<String, Object> args = new HashMap<>();
        args.put("question", question);
        args.put("kbId", kbId);
        return withAudit("ask_knowledge", args, () -> doAskKnowledge(question, kbId));
    }

    private Map<String, Object> doAskKnowledge(String question, Long kbId) {
        try {
            // 1. 构建 Graph Agent
            CompiledGraph graph = ragGraphAgent.buildGraph();

            // 2. 构建初始 State
            Map<String, Object> initialState = new HashMap<>();
            initialState.put(StateKeys.QUESTION, question);
            initialState.put(StateKeys.CATEGORY, "unknown");
            initialState.put(StateKeys.KB_IDS, kbId != null ? List.of(kbId) : null);
            initialState.put(StateKeys.EFFECTIVE_KB_ID, kbId);
            initialState.put(StateKeys.DOCUMENTS, new ArrayList<>());
            initialState.put(StateKeys.CONTEXT, "");
            initialState.put(StateKeys.ANSWER, "");
            initialState.put(StateKeys.EMOTION, "neutral");
            initialState.put(StateKeys.ESCALATE, "false");
            initialState.put(StateKeys.CONVERSATION_ID, "mcp-" + UUID.randomUUID().toString().substring(0, 8));

            // 3. 执行全流程 Graph Agent
            com.alibaba.cloud.ai.graph.OverAllState finalState = graph.invoke(initialState).orElse(null);
            if (finalState == null) {
                return fallbackSearch(question, kbId);
            }
            Map<String, Object> finalData = finalState.data();

            // 4. 提取结果
            String answer = (String) finalData.getOrDefault(StateKeys.ANSWER, "");
            String steps = (String) finalData.getOrDefault(StateKeys.STEPS, "");
            String category = (String) finalData.getOrDefault(StateKeys.CATEGORY, "unknown");

            Map<String, Object> result = new HashMap<>();
            result.put("answer", answer);
            result.put("steps", steps);
            result.put("category", category);
            result.put("kbId", kbId);
            return result;

        } catch (Exception e) {
            // Graph 执行异常时降级返回纯检索结果
            log.error("[MCP askKnowledge] Graph Agent 执行失败, 降级检索, question={}", question, e);
            return fallbackSearch(question, kbId);
        }
    }

    /**
     * Graph 执行失败时的纯检索降级
     */
    private Map<String, Object> fallbackSearch(String query, Long kbId) {
        try {
            String filterExpression = kbId != null ? "kb_id in [" + kbId + "]" : null;
            List<Document> docs = hybridSearchService.search(query, DEFAULT_TOP_K, filterExpression);

            List<Map<String, Object>> chunks = docs.stream().map(doc -> {
                Map<String, Object> chunk = new HashMap<>();
                chunk.put("content", doc.getText());
                Map<String, Object> metadata = doc.getMetadata() != null ? doc.getMetadata() : new HashMap<>();
                chunk.put("source", metadata.get("source"));
                chunk.put("score", doc.getScore());
                chunk.put("page", metadata.getOrDefault("page", null));
                chunk.put("kbName", metadata.get("kb_name"));
                chunk.put("version", metadata.get("version"));
                return chunk;
            }).collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("answer", "[Graph Agent 执行失败，已降级为纯检索]");
            result.put("chunks", chunks);
            result.put("total", chunks.size());
            return result;
        } catch (Exception ex) {
            return McpErrorResponseHelper.buildInternalError();
        }
    }

    /**
     * 查询上传任务状态
     * <p>按 task_id 查询文件上传任务的进度、阶段和结果 | Query upload task status by task_id, including progress, stage and result.</p>
     *
     * @param taskId 任务ID（必填）| Task ID (required)
     * @return SUCCESS: {taskId, status, fileName, kbId, kbName, version, chunkCount, completeTime}<br>
     *         PROCESSING: {taskId, status, stage, progress, fileName, kbId}<br>
     *         FAILED: {taskId, status, stage, errorMessage, errorStage, completeTime}<br>
     *         不存在: {isError: true, code: 40401, message: "task not found"}
     */
    @Tool(name = "get_upload_status",
            description = "查询上传任务状态 | Query upload task status by task_id")
    public Map<String, Object> getUploadStatus(
            @ToolParam(description = "任务ID | Task ID", required = true) String taskId) {
        Map<String, Object> args = new HashMap<>();
        args.put("taskId", taskId);
        return withAudit("get_upload_status", args, () -> doGetUploadStatus(taskId));
    }

    private Map<String, Object> doGetUploadStatus(String taskId) {
        McpUploadTask task = mcpUploadTaskMapper.selectByTaskId(taskId);
        if (task == null) {
            return McpErrorResponseHelper.buildError(40401, "task not found");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("status", task.getStatus());
        result.put("fileName", task.getFileName());
        result.put("kbId", task.getKbId());

        switch (task.getStatus()) {
            case "SUCCESS":
                result.put("kbName", task.getKbName());
                result.put("version", queryVersionByKbAndFileName(task.getKbId(), task.getFileName()));
                result.put("chunkCount", parseVectorIdsCount(task.getVectorIds()));
                result.put("completeTime", task.getCompleteTime());
                break;
            case "PROCESSING":
                result.put("stage", task.getStage());
                result.put("progress", task.getProgress());
                break;
            case "FAILED":
                result.put("stage", task.getStage());
                result.put("errorMessage", task.getErrorMessage());
                result.put("errorStage", task.getErrorStage());
                result.put("completeTime", task.getCompleteTime());
                break;
            default:
                // PENDING 等其他状态：返回基础信息
                result.put("stage", task.getStage());
                result.put("progress", task.getProgress());
                break;
        }
        return result;
    }

    /**
     * 查询知识库中指定文件名的最新版本号
     */
    private String queryVersionByKbAndFileName(Long kbId, String fileName) {
        if (kbId == null || fileName == null) {
            return null;
        }
        try {
            List<com.xushu.rag.entity.Document> docs = documentMapper.selectByKbIdAndOriginalName(kbId, fileName);
            if (docs != null && !docs.isEmpty()) {
                return docs.get(0).getVersion();
            }
        } catch (Exception e) {
            // 查询失败不影响主流程，版本号返回 null
        }
        return null;
    }

    /**
     * 解析 vector_ids JSON 字符串，返回向量数量（即 chunk 数）
     */
    private int parseVectorIdsCount(String vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return 0;
        }
        try {
            JSONArray array = JSON.parseArray(vectorIds);
            return array.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 上传文件到知识库（URL方式）
     * <p>通过文件HTTP URL下载并上传到知识库，复用 KnowledgeUploadService 的解析、切片、向量化链路。</p>
     *
     * @param fileUrl      文件HTTP URL（必填）| File HTTP URL (required)
     * @param kbName       目标知识库名称（可选，auto_classify=false 时必填）| Target KB name
     * @param autoClassify 是否自动分类（默认true）| Auto classify (default true)
     * @return 同步成功: {taskId, fileName, kbId, kbName, version, status, chunkCount}<br>
     *         异步处理: {taskId, status: "PROCESSING"}<br>
     *         错误: {isError: true, code, message}
     */
    @Tool(name = "upload_kb_file",
            description = "通过文件URL上传到知识库 | Upload file to knowledge base by URL")
    public Map<String, Object> uploadKbFile(
            @ToolParam(description = "文件HTTP/HTTPS URL | File HTTP/HTTPS URL", required = true) String fileUrl,
            @ToolParam(description = "目标知识库名称（auto_classify=false时必填）| Target KB name", required = false) String kbName,
            @ToolParam(description = "是否自动分类（默认true）| Auto classify", required = false) Boolean autoClassify) {
        Map<String, Object> args = new HashMap<>();
        args.put("fileUrl", fileUrl);
        args.put("kbName", kbName);
        args.put("autoClassify", autoClassify);
        return withAudit("upload_kb_file", args, () -> doUploadKbFile(fileUrl, kbName, autoClassify));
    }

    private Map<String, Object> doUploadKbFile(String fileUrl, String kbName, Boolean autoClassify) {
        boolean shouldAutoClassify = (autoClassify == null) ? true : autoClassify;
        if (!shouldAutoClassify && (kbName == null || kbName.isEmpty())) {
            return McpErrorResponseHelper.buildError(40003, "kb_name is required when auto_classify=false");
        }

        String taskId = UUID.randomUUID().toString();

        // 下载文件
        byte[] fileBytes;
        String targetFileName;
        try {
            fileBytes = downloadFromUrl(fileUrl);
        } catch (IllegalArgumentException e) {
            return McpErrorResponseHelper.buildError(40001, e.getMessage());
        } catch (Exception e) {
            return McpErrorResponseHelper.buildError(40002, "failed to download file_url: " + e.getMessage());
        }
        if (fileBytes.length > MAX_URL_SIZE) {
            return McpErrorResponseHelper.buildError(40001, "file exceeds 100MB limit");
        }
        targetFileName = extractFileNameFromUrl(fileUrl);

        // 创建任务记录
        McpUploadTask task = McpUploadTask.builder()
                .taskId(taskId)
                .fileName(targetFileName)
                .fileSize((long) fileBytes.length)
                .fileUrl(fileUrl)
                .kbName(kbName)
                .status("PROCESSING")
                .stage("PARSING")
                .progress(0)
                .createTime(new Date())
                .updateTime(new Date())
                .build();
        try {
            mcpUploadTaskMapper.insert(task);
        } catch (Exception ignored) {}

        // 大文件异步上传
        if (fileBytes.length >= SYNC_THRESHOLD) {
            final byte[] finalFileBytes = fileBytes;
            final String finalTargetFileName = targetFileName;
            final boolean finalShouldAutoClassify = shouldAutoClassify;
            try {
                mcpUploadExecutor.submit(() -> executeUploadAsync(task, finalFileBytes, finalTargetFileName,
                        kbName, finalShouldAutoClassify));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                task.setStatus("FAILED");
                task.setErrorMessage("upload service busy, please retry later");
                task.setErrorStage("QUEUE");
                task.setUpdateTime(new Date());
                task.setCompleteTime(new Date());
                try {
                    mcpUploadTaskMapper.updateById(task);
                } catch (Exception ignored) {}
                return McpErrorResponseHelper.buildError(50301, "upload service busy, please retry later");
            }
            Map<String, Object> asyncResult = new HashMap<>();
            asyncResult.put("taskId", taskId);
            asyncResult.put("status", "PROCESSING");
            asyncResult.put("message", "large file uploading asynchronously, please poll get_upload_status");
            return asyncResult;
        }

        // 小文件同步上传
        MultipartFile multipartFile = new ByteArrayMultipartFile(targetFileName, fileBytes);
        UploadResult uploadResult = knowledgeUploadService.uploadFile(
                multipartFile, null, kbName, shouldAutoClassify,
                DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        updateTaskResult(task, uploadResult);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("fileName", uploadResult.getFileName());
        result.put("kbId", uploadResult.getKbId());
        result.put("kbName", uploadResult.getKbName());
        result.put("version", uploadResult.getVersion());
        result.put("status", uploadResult.getStatus());
        result.put("chunkCount", uploadResult.getChunkCount());
        return result;
    }

    /**
     * 异步执行上传任务（大文件路径）
     */
    private void executeUploadAsync(McpUploadTask task, byte[] fileBytes, String targetFileName,
                                    String kbName, boolean shouldAutoClassify) {
        try {
            MultipartFile multipartFile = new ByteArrayMultipartFile(targetFileName, fileBytes);
            UploadResult uploadResult = knowledgeUploadService.uploadFile(
                    multipartFile, null, kbName, shouldAutoClassify,
                    DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
            updateTaskResult(task, uploadResult);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setErrorMessage("async upload failed: " + e.getMessage());
            task.setErrorStage("UPLOAD");
            task.setUpdateTime(new Date());
            task.setCompleteTime(new Date());
            try {
                mcpUploadTaskMapper.updateById(task);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 更新任务记录为上传结果
     */
    private void updateTaskResult(McpUploadTask task, UploadResult uploadResult) {
        task.setStatus(uploadResult.getStatus());
        task.setProgress(100);
        task.setKbId(uploadResult.getKbId());
        task.setKbName(uploadResult.getKbName());
        task.setUpdateTime(new Date());
        task.setCompleteTime(new Date());
        if ("FAILED".equals(uploadResult.getStatus())) {
            task.setErrorMessage(uploadResult.getErrorMessage());
            task.setErrorStage("UPLOAD");
        }
        try {
            mcpUploadTaskMapper.updateById(task);
        } catch (Exception e) {
            // 更新失败不影响返回结果
        }
    }

    /**
     * 从 URL 下载文件内容
     */
    private byte[] downloadFromUrl(String fileUrl) {
        if (fileUrl == null || (!fileUrl.startsWith("http://") && !fileUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("file_url must start with http:// or https://");
        }
        byte[] bytes = restTemplate.getForObject(fileUrl, byte[].class);
        if (bytes == null) {
            throw new RuntimeException("downloaded empty content from file_url");
        }
        return bytes;
    }

    /**
     * 从 URL 提取文件名
     */
    private String extractFileNameFromUrl(String fileUrl) {
        if (fileUrl == null) {
            return "uploaded_file";
        }
        int queryIdx = fileUrl.indexOf('?');
        String path = queryIdx >= 0 ? fileUrl.substring(0, queryIdx) : fileUrl;
        int slashIdx = path.lastIndexOf('/');
        String name = slashIdx >= 0 ? path.substring(slashIdx + 1) : path;
        return (name == null || name.isEmpty()) ? "uploaded_file" : name;
    }

    /**
     * 统一审计 + 异常兜底包装器
     * <p>拦截工具执行：正常返回审计为 SUCCESS；返回 isError=true 审计为 FAILED；
     * 抛异常审计为 FAILED 并返回 50000 内部错误。</p>
     *
     * @param toolName 工具名称
     * @param args     调用参数（用于审计）
     * @param action   实际业务逻辑
     * @return 工具返回结果
     */
    private Map<String, Object> withAudit(String toolName, Object args, Supplier<Map<String, Object>> action) {
        long start = System.currentTimeMillis();
        String status = "SUCCESS";
        String errorMessage = null;
        Map<String, Object> result;
        try {
            result = action.get();
            if (Boolean.TRUE.equals(result.get("isError"))) {
                status = "FAILED";
            }
        } catch (Exception e) {
            status = "FAILED";
            errorMessage = e.getMessage();
            result = McpErrorResponseHelper.buildInternalError();
        }
        long duration = System.currentTimeMillis() - start;
        mcpAuditLogger.logAsync(toolName, getCallerSecretKeyId(), args, result, duration, status, errorMessage);
        return result;
    }

    /**
     * 从当前请求上下文获取鉴权 Filter 设置的 callerSecretKeyId
     */
    private Long getCallerSecretKeyId() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            Object id = request.getAttribute("callerSecretKeyId");
            return id instanceof Long ? (Long) id : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 基于字节数组的 MultipartFile 简单实现
     * <p>用于将 base64 解码后的字节数组适配为 KnowledgeUploadService 所需的 MultipartFile。</p>
     */
    private static class ByteArrayMultipartFile implements MultipartFile {
        private final String fileName;
        private final byte[] content;

        ByteArrayMultipartFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content != null ? content : new byte[0];
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public ByteArrayResource getResource() {
            return new ByteArrayResource(content, fileName);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                fos.write(content);
            }
        }
    }
}
