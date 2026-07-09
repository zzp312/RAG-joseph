package com.xushu.rag.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.common.ResultUtils;
import com.xushu.rag.entity.AliOssFile;
import com.xushu.rag.pojo.dto.QueryFileDTO;
import com.xushu.rag.entity.McpSecretKey;
import com.xushu.rag.mapper.McpSecretKeyMapper;
import com.xushu.rag.pojo.dto.UploadResult;
import com.xushu.rag.service.AliOssFileService;
import com.xushu.rag.service.DocumentService;
import com.xushu.rag.service.KnowledgeBaseService;
import com.xushu.rag.service.KnowledgeUploadService;
import com.xushu.rag.service.MilvusV2InsertService;
import com.xushu.rag.utils.AliOssUtil;
import com.xushu.rag.utils.PythonScriptExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "KnowledgeController", description = "知识库管理接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/knowledge")
public class KnowledgeController {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private MilvusV2InsertService milvusV2InsertService;

    @Autowired
    private AliOssUtil aliOssUtil;

    @Autowired
    private TokenTextSplitter tokenTextSplitter;

    @Autowired
    private AliOssFileService aliOssFileService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private PythonScriptExecutor pythonScriptExecutor;

    @Autowired
    private KnowledgeUploadService knowledgeUploadService;

    @Autowired
    private McpSecretKeyMapper mcpSecretKeyMapper;

    @Operation(summary = "upload", description = "上传附件接口（旧版，兼容原有逻辑）")
    @PostMapping(value = "file/upload", headers = "content-type=multipart/form-data")
    public BaseResponse upload(@RequestParam("file") List<MultipartFile> files) {
        if (files.isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请上传文件");
        }

        for (MultipartFile file : files) {
            try {
                String originalFilename = file.getOriginalFilename();
                String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                String baseName = originalFilename.substring(0, originalFilename.lastIndexOf("."));
                String ossVersion = "v1.0." + System.currentTimeMillis();
                String objectName = baseName + "_" + ossVersion + extension;
                String url = aliOssUtil.upload(file.getBytes(), objectName);

                Resource resource = file.getResource();
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<org.springframework.ai.document.Document> documents = reader.read();

                List<org.springframework.ai.document.Document> splitDocuments = tokenTextSplitter.apply(documents);
                log.info("开始向量化，共{}个分块", splitDocuments.size());
                // DashScope embedding 单次最多25条，分批写入（V2 SDK，绕过 sparse_vector 校验）
                final int EMBED_BATCH = 20;
                for (int batch = 0; batch < splitDocuments.size(); batch += EMBED_BATCH) {
                    int toIdx = Math.min(batch + EMBED_BATCH, splitDocuments.size());
                    milvusV2InsertService.insertDocuments(splitDocuments.subList(batch, toIdx));
                }
                log.info("向量化完成");

                long currMillis = System.currentTimeMillis();
                aliOssFileService.save(AliOssFile.builder()
                        .fileName(originalFilename)
                        .vectorId(JSON.toJSONString(splitDocuments.stream().map(org.springframework.ai.document.Document::getId).collect(Collectors.toList())))
                        .url(url)
                        .createTime(new Date(currMillis))
                        .updateTime(new Date(currMillis))
                        .build());

            } catch (IOException e) {
                log.error("上传文件失败", e);
                return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "上传文件失败");
            } catch (Exception e) {
                log.error("上传文件失败", e);
                return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "向量化失败");
            }
        }
        return ResultUtils.success("文件上传成功");
    }

    @Operation(summary = "uploadWithKb", description = "上传附件并自动分类到知识库")
    @PostMapping(value = "file/upload-with-kb", headers = "content-type=multipart/form-data")
    public BaseResponse uploadWithKb(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String kbName,
            @RequestParam(required = false, defaultValue = "true") Boolean autoClassify,
            @RequestParam(required = false, defaultValue = "300") Integer chunkSize,
            @RequestParam(required = false, defaultValue = "50") Integer chunkOverlap) {

        if (files.isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请上传文件");
        }

        List<UploadResult> uploadResults = knowledgeUploadService.uploadFiles(
                files, kbId, kbName, autoClassify, chunkSize, chunkOverlap);

        List<Map<String, Object>> results = new ArrayList<>();
        for (UploadResult r : uploadResults) {
            Map<String, Object> result = new HashMap<>();
            result.put("fileName", r.getFileName());
            result.put("kbId", r.getKbId());
            result.put("kbName", r.getKbName());
            result.put("version", r.getVersion());
            result.put("status", r.getStatus());
            if (r.getErrorMessage() != null) {
                result.put("errorMessage", r.getErrorMessage());
            }
            results.add(result);
        }

        return ResultUtils.success(results);
    }

    /**
     * MCP Agent 专用上传。走 X-MCP-Secret-Key 鉴权，不走 JWT。
     * JWT 拦截器已排除 /api/v1/knowledge/file/mcp-upload 路径。
     */
    @PostMapping(value = "file/mcp-upload", headers = "content-type=multipart/form-data")
    public BaseResponse mcpUpload(
            @RequestHeader(value = "X-MCP-Secret-Key", required = false) String secretKey,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String kbName,
            @RequestParam(required = false, defaultValue = "true") Boolean autoClassify,
            @RequestParam(required = false, defaultValue = "800") Integer chunkSize,
            @RequestParam(required = false, defaultValue = "100") Integer chunkOverlap) {

        // MCP SecretKey 鉴权
        if (secretKey == null || secretKey.isEmpty()) {
            return ResultUtils.error(401, "Missing X-MCP-Secret-Key header");
        }
        McpSecretKey keyEntity = mcpSecretKeyMapper.selectActiveBySecretKey(secretKey);
        if (keyEntity == null) {
            return ResultUtils.error(401, "Invalid or inactive X-MCP-Secret-Key");
        }

        // 复用 KnowledgeUploadService
        UploadResult result = knowledgeUploadService.uploadFile(
                file, kbId, kbName, autoClassify, chunkSize, chunkOverlap);

        if ("failed".equalsIgnoreCase(result.getStatus())) {
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, result.getErrorMessage());
        }
        return ResultUtils.success(result);
    }

    @Operation(summary = "suggestKb", description = "根据文件内容建议知识库分类")
    @PostMapping(value = "file/suggest-kb", headers = "content-type=multipart/form-data")
    public BaseResponse suggestKb(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请上传文件");
        }

        try {
            Path tempFilePath = saveTempFile(file);
            String tempPathStr = tempFilePath.toString();

            JSONObject parseResult;
            String contentText;
            try {
                parseResult = pythonScriptExecutor.executeParser(tempPathStr);
                if (parseResult.containsKey("error")) {
                    throw new Exception(parseResult.getString("error"));
                }
                contentText = parseResult.getString("text");
            } catch (Exception e) {
                log.warn("Python解析失败，使用Java解析: {}", e.getMessage());
                Resource resource = file.getResource();
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<org.springframework.ai.document.Document> documents = reader.read();
                contentText = documents.stream()
                        .map(org.springframework.ai.document.Document::getText)
                        .collect(Collectors.joining("\n"));
            }

            Files.deleteIfExists(tempFilePath);

            return knowledgeBaseService.suggestKnowledgeBase(contentText);

        } catch (IOException e) {
            log.error("解析文件失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "解析文件失败");
        }
    }

    @Operation(summary = "contents", description = "文件查询")
    @GetMapping("/contents")
    public BaseResponse queryFiles(QueryFileDTO request) {
        if (request.getPage() == null || request.getPageSize() == null) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "page 或 pageSize为空");
        }
        return aliOssFileService.queryPage(request);
    }

    @Operation(summary = "delete", description = "文件删除")
    @DeleteMapping("/delete")
    public BaseResponse deleteFiles(@RequestParam List<Long> ids) {
        return aliOssFileService.deleteFiles(ids);
    }

    @Operation(summary = "download", description = "文件下载")
    @GetMapping("/download")
    public BaseResponse downloadFiles(@RequestParam List<Long> ids) {
        return aliOssFileService.downloadFiles(ids);
    }

    @Operation(summary = "listByKb", description = "根据知识库ID查询文档列表")
    @GetMapping("/list-by-kb/{kbId}")
    public BaseResponse listDocumentsByKbId(@PathVariable Long kbId) {
        return documentService.listDocumentsByKbId(kbId);
    }

    @Operation(summary = "docHistory", description = "查询文档版本历史")
    @GetMapping("/doc-history")
    public BaseResponse getDocumentHistory(@RequestParam Long kbId, @RequestParam String originalName) {
        return documentService.getDocumentHistory(kbId, originalName);
    }

    @Operation(summary = "latestDocs", description = "获取知识库最新版本文档")
    @GetMapping("/latest-docs/{kbId}")
    public BaseResponse getLatestDocuments(@PathVariable Long kbId) {
        return documentService.getLatestDocuments(kbId);
    }

    /**
     * 批量查询多个知识库的最新版本文档
     * <p>用于前端知识库级联选择：选中多个知识库后获取对应文件列表</p>
     *
     * @param kbIds 知识库ID列表
     * @return 合并后的文档列表
     * @author Joseph
     */
    @Operation(summary = "latestDocsBatch", description = "批量获取多个知识库的最新版本文档")
    @GetMapping("/latest-docs-batch")
    public BaseResponse getLatestDocumentsBatch(@RequestParam(required = false) List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            // 无知识库筛选时返回全部文档
            return aliOssFileService.queryPage(new QueryFileDTO() {{
                setPage(0);
                setPageSize(500);
            }});
        }
        List<java.util.Map<String, Object>> allDocs = new java.util.ArrayList<>();
        for (Long kbId : kbIds) {
            BaseResponse response = documentService.getLatestDocuments(kbId);
            if (response.getCode() == 0 && response.getData() != null) {
                @SuppressWarnings("unchecked")
                List<com.xushu.rag.entity.Document> docs = (List<com.xushu.rag.entity.Document>) response.getData();
                for (com.xushu.rag.entity.Document doc : docs) {
                    java.util.Map<String, Object> docInfo = new java.util.HashMap<>();
                    docInfo.put("id", doc.getId());
                    docInfo.put("fileName", doc.getOriginalName());
                    docInfo.put("version", doc.getVersion());
                    docInfo.put("kbId", doc.getKbId());
                    docInfo.put("fileType", doc.getFileType());
                    allDocs.add(docInfo);
                }
            }
        }
        return ResultUtils.success(allDocs);
    }

    private Path saveTempFile(MultipartFile file) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        String baseName = originalFilename.substring(0, originalFilename.lastIndexOf("."));
        String version = "v1.0." + System.currentTimeMillis();
        Path tempFilePath = Paths.get(tempDir, baseName + "_" + version + extension);
        Files.copy(file.getInputStream(), tempFilePath);
        return tempFilePath;
    }
}
