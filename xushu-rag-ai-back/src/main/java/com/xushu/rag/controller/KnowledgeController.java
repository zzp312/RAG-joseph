package com.xushu.rag.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.common.ResultUtils;
import com.xushu.rag.entity.AliOssFile;
import com.xushu.rag.entity.DocumentPage;
import com.xushu.rag.entity.KnowledgeBase;
import com.xushu.rag.pojo.dto.QueryFileDTO;
import com.xushu.rag.service.AliOssFileService;
import com.xushu.rag.service.DocumentPageService;
import com.xushu.rag.service.DocumentService;
import com.xushu.rag.service.KnowledgeBaseService;
import com.xushu.rag.service.impl.DocumentPageServiceImpl;
import com.xushu.rag.service.MilvusV2InsertService;
import com.xushu.rag.utils.AliOssUtil;
import com.xushu.rag.utils.ImageDescriber;
import com.xushu.rag.utils.ImageExtractor;
import com.xushu.rag.utils.JavaDocumentParser;
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

    /**
     * 页面原文服务（Small-to-Big回表查询）
     *
     * @author Joseph
     */
    @Autowired
    private DocumentPageService documentPageService;

    @Autowired
    private PythonScriptExecutor pythonScriptExecutor;

    /**
     * Java原生PDF解析器（替代Python脚本）
     *
     * @author Joseph
     */
    @Autowired
    private JavaDocumentParser javaDocumentParser;

    /**
     * PDF图片提取器（用于多模态图片理解）
     *
     * @author Joseph
     */
    @Autowired
    private ImageExtractor imageExtractor;

    /**
     * 多模态图片描述器（通义千问VL）
     *
     * @author Joseph
     */
    @Autowired
    private ImageDescriber imageDescriber;

    /**
     * 解析文件内容，优先使用JavaDocumentParser，PythonScriptExecutor作为备用
     * <p>解析策略：PDF/DOCX/XLSX→JavaDocumentParser→Tika降级；其他→PythonScriptExecutor→Tika降级</p>
     *
     * @author Joseph
     */
    private ParsedContent parseDocumentContent(MultipartFile file, Path tempFilePath) throws IOException {
        String tempPathStr = tempFilePath.toString();
        String originalFilename = file.getOriginalFilename();
        String lowerName = originalFilename != null ? originalFilename.toLowerCase() : "";
        boolean isJavaSupported = lowerName.endsWith(".pdf")
                || lowerName.endsWith(".docx")
                || lowerName.endsWith(".xlsx")
                || lowerName.endsWith(".xls");

        JSONObject parseResult = new JSONObject();
        String contentText;

        try {
            if (isJavaSupported) {
                // PDF/DOCX/XLSX：使用Java原生解析
                log.info("使用JavaDocumentParser解析: {}", originalFilename);
                parseResult = javaDocumentParser.parse(tempPathStr);
                if (parseResult.containsKey("error")) {
                    throw new Exception(parseResult.getString("error"));
                }
                contentText = parseResult.getString("text");
            } else {
                // 其他格式：尝试Python解析，失败则降级Tika
                log.info("使用PythonScriptExecutor解析非PDF文件: {}", originalFilename);
                parseResult = pythonScriptExecutor.executeParser(tempPathStr);
                if (parseResult.containsKey("error")) {
                    throw new Exception(parseResult.getString("error"));
                }
                contentText = parseResult.getString("text");
            }
        } catch (Exception e) {
            log.warn("主解析器失败，使用Tika降级解析: {}", e.getMessage());
            Resource resource = file.getResource();
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<org.springframework.ai.document.Document> documents = reader.read();
            contentText = documents.stream()
                    .map(org.springframework.ai.document.Document::getText)
                    .collect(Collectors.joining("\n"));
            parseResult = new JSONObject();
            parseResult.put("text", contentText);
        }

        return new ParsedContent(parseResult, contentText);
    }

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

        List<Map<String, Object>> results = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                String originalFilename = file.getOriginalFilename();
                String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                String baseName = originalFilename.substring(0, originalFilename.lastIndexOf("."));
                String ossVersion = "v1.0." + System.currentTimeMillis();
                String objectName = baseName + "_" + ossVersion + extension;
                String url = aliOssUtil.upload(file.getBytes(), objectName);

                Path tempFilePath = saveTempFile(file);
                String tempPathStr = tempFilePath.toString();

                // 使用统一解析入口（PDF→JavaDocumentParser，其他→Python→Tika降级）
                ParsedContent parsedContent = parseDocumentContent(file, tempFilePath);
                JSONObject parseResult = parsedContent.getParseResult();
                String contentText = parsedContent.getContentText();

                Long targetKbId = kbId;
                String targetKbName = kbName;

                if (autoClassify && targetKbId == null) {
                    BaseResponse suggestResponse = knowledgeBaseService.suggestKnowledgeBase(contentText);
                    if (suggestResponse.getCode() == 0 && suggestResponse.getData() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> suggestData = (Map<String, Object>) suggestResponse.getData();
                        targetKbName = (String) suggestData.get("suggested_kb_name");
                        Boolean isNew = (Boolean) suggestData.get("is_new");

                        if (isNew) {
                            if (targetKbName == null || targetKbName.isEmpty()) {
                                targetKbName = generateKbName(contentText);
                            }
                            BaseResponse createResponse = knowledgeBaseService.createKnowledgeBase(targetKbName, null, null);
                            if (createResponse.getCode() == 0) {
                                KnowledgeBase newKb = (KnowledgeBase) createResponse.getData();
                                targetKbId = newKb.getId();
                            }
                        } else {
                            targetKbId = (Long) suggestData.get("kb_id");
                            if (targetKbId == null) {
                                BaseResponse listResponse = knowledgeBaseService.listActiveKnowledgeBases();
                                List<KnowledgeBase> kbs = (List<KnowledgeBase>) listResponse.getData();
                                for (KnowledgeBase kb : kbs) {
                                    if (kb.getName().equals(targetKbName)) {
                                        targetKbId = kb.getId();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

                if (targetKbId == null) {
                    BaseResponse listResponse = knowledgeBaseService.listActiveKnowledgeBases();
                    List<KnowledgeBase> kbs = (List<KnowledgeBase>) listResponse.getData();
                    if (!kbs.isEmpty()) {
                        targetKbId = kbs.get(0).getId();
                        targetKbName = kbs.get(0).getName();
                    } else {
                        BaseResponse createResponse = knowledgeBaseService.createKnowledgeBase("通用知识库", null, null);
                        if (createResponse.getCode() == 0) {
                            KnowledgeBase newKb = (KnowledgeBase) createResponse.getData();
                            targetKbId = newKb.getId();
                            targetKbName = newKb.getName();
                        }
                    }
                }

                String version = documentService.generateVersion(targetKbId, originalFilename);

                // 确保targetKbName非null，避免Spring AI Document metadata不允许null值
                if (targetKbName == null || targetKbName.isEmpty()) {
                    if (targetKbId != null) {
                        try {
                            KnowledgeBase kb = knowledgeBaseService.getById(targetKbId);
                            if (kb != null) {
                                targetKbName = kb.getName();
                            }
                        } catch (Exception e) {
                            log.warn("查询知识库失败, kbId={}", targetKbId, e);
                        }
                    }
                    if (targetKbName == null || targetKbName.isEmpty()) {
                        targetKbName = "未分类";
                    }
                }

                List<org.springframework.ai.document.Document> aiDocuments = new ArrayList<>();
                if (parseResult.containsKey("chunks") && parseResult.getJSONArray("chunks") != null
                        && !parseResult.getJSONArray("chunks").isEmpty()) {
                    JSONArray chunks = parseResult.getJSONArray("chunks");

                    // 1. 保存页面全文到 document_pages 表（MySQL，用于检索回表）
                    java.util.Map<Integer, StringBuilder> pageFullTexts = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < chunks.size(); i++) {
                        JSONObject c = chunks.getJSONObject(i);
                        Integer p = c.getInteger("page");
                        if (p != null) {
                            pageFullTexts.computeIfAbsent(p, k -> new StringBuilder())
                                    .append(c.getString("text")).append("\n");
                        }
                    }
                    java.util.List<DocumentPage> pageEntities = new java.util.ArrayList<>();
                    for (java.util.Map.Entry<Integer, StringBuilder> entry : pageFullTexts.entrySet()) {
                        String fullText = entry.getValue().toString().trim();
                        // 超长页面分段存储（每段8000字符，段间重叠200字符）
                        pageEntities.addAll(DocumentPageServiceImpl.splitPage(
                                originalFilename, version, entry.getKey(), fullText, 8000, 200));
                    }
                    documentPageService.batchSave(pageEntities);

                    // 2. 每页文本用TokenTextSplitter拆成小块（Small-to-Big: 小块embedding）
                    for (int i = 0; i < chunks.size(); i++) {
                        JSONObject chunk = chunks.getJSONObject(i);
                        Integer page = chunk.getInteger("page");
                        String chunkType = chunk.getString("type");
                        String chunkTypeUpper = chunkType != null ? chunkType.toUpperCase() : "TEXT";

                        // TEXT用TokenTextSplitter拆分；TABLE/IMAGE截断(embedding限2048token≈1500字)，不拆分
                        String pageText = chunk.getString("text");
                        List<org.springframework.ai.document.Document> splitDocs;
                        if ("TEXT".equalsIgnoreCase(chunkTypeUpper)) {
                            try {
                                splitDocs = tokenTextSplitter.apply(
                                        java.util.Collections.singletonList(
                                                new org.springframework.ai.document.Document(pageText)));
                            } catch (Exception e) {
                                log.warn("TokenTextSplitter拆分失败，保留整块: page={}", page);
                                splitDocs = java.util.Collections.singletonList(
                                        new org.springframework.ai.document.Document(pageText));
                            }
                        } else {
                            // TABLE/IMAGE不拆（避免表头丢失），超长则截断
                            String safe = pageText.length() > 1500
                                    ? pageText.substring(0, 1500) : pageText;
                            splitDocs = java.util.Collections.singletonList(
                                    new org.springframework.ai.document.Document(safe));
                        }

                        // 诊断日志：chunk切分质量
                        log.info("[Chunk切分] source={}, page={}, type={}, 原文{}字 → 切为{}个chunk",
                                originalFilename, page, chunkTypeUpper,
                                pageText.length(), splitDocs.size());

                        // 每个小块存入Milvus，metadata只存指针(page, source)
                        for (org.springframework.ai.document.Document splitDoc : splitDocs) {
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("source", originalFilename);
                            metadata.put("kb_id", targetKbId);
                            metadata.put("kb_name", targetKbName);
                            metadata.put("version", version);
                            if (page != null) {
                                metadata.put("page", page);
                            }
                            metadata.put("chunk_type", chunkTypeUpper);
                            // 不再存parent_page_text，检索时回表查document_pages
                            aiDocuments.add(new org.springframework.ai.document.Document(
                                    splitDoc.getText(), metadata));
                        }
                    }
                } else {
                    Resource resource = file.getResource();
                    TikaDocumentReader reader = new TikaDocumentReader(resource);
                    List<org.springframework.ai.document.Document> originalDocs = reader.read();
                    for (org.springframework.ai.document.Document doc : originalDocs) {
                        // 过滤掉Tika metadata中的null值，Spring AI Document不允许null
                        Map<String, Object> metadata = new HashMap<>();
                        doc.getMetadata().forEach((k, v) -> {
                            if (v != null) {
                                metadata.put(k, v);
                            }
                        });
                        metadata.put("source", originalFilename);
                        metadata.put("kb_id", targetKbId);
                        metadata.put("kb_name", targetKbName);
                        metadata.put("version", version);
                        // Tika降级方案：chunk_type默认TEXT
                        metadata.put("chunk_type", "TEXT");
                        aiDocuments.add(new org.springframework.ai.document.Document(doc.getText(), metadata));
                    }
                    aiDocuments = tokenTextSplitter.apply(aiDocuments);
                }

                // DashScope embedding 单次最多25条，分批写入（V2 SDK，绕过 sparse_vector 校验）
                final int EMBED_BATCH_SIZE = 20;
                for (int batch = 0; batch < aiDocuments.size(); batch += EMBED_BATCH_SIZE) {
                    int toIdx = Math.min(batch + EMBED_BATCH_SIZE, aiDocuments.size());
                    milvusV2InsertService.insertDocuments(aiDocuments.subList(batch, toIdx));
                }
                log.info("向量化完成，文件: {}，共{}个分块", originalFilename, aiDocuments.size());

                String vectorIds = JSON.toJSONString(aiDocuments.stream().map(org.springframework.ai.document.Document::getId).collect(Collectors.toList()));

                com.xushu.rag.entity.Document document = com.xushu.rag.entity.Document.builder()
                        .kbId(targetKbId)
                        .fileName(objectName)
                        .originalName(originalFilename)
                        .filePath(url)
                        .fileType(extension.substring(1).toLowerCase())
                        .version(version)
                        .status("COMPLETED")
                        .vectorId(vectorIds)
                        .url(url)
                        .createTime(new Date())
                        .updateTime(new Date())
                        .build();

                documentService.createDocument(document);

                // 异步处理图片（多模态语义理解），不阻塞主流程
                // 注意：临时文件由异步线程负责清理，避免竞态条件
                final Long finalKbId = targetKbId;
                final String finalKbName = targetKbName;
                final String finalVersion = version;
                final String tempPathForImage = tempPathStr;
                final String ossUrlForImage = url;
                final boolean isPdfFile = originalFilename != null
                        && originalFilename.toLowerCase().endsWith(".pdf");
                final boolean isDocxFile = originalFilename != null
                        && originalFilename.toLowerCase().endsWith(".docx");

                if (isPdfFile || isDocxFile) {
                    final boolean isPdf = isPdfFile; // 用于线程内判断
                    new Thread(() -> {
                        processImagesAsync(tempPathForImage, isPdf, finalKbId, finalKbName,
                                finalVersion, ossUrlForImage, originalFilename);
                        try {
                            Files.deleteIfExists(Paths.get(tempPathForImage));
                        } catch (IOException ignored) {
                        }
                    }, "image-processing-" + originalFilename).start();
                } else {
                    Files.deleteIfExists(tempFilePath);
                }

                Map<String, Object> result = new HashMap<>();
                result.put("fileName", originalFilename);
                result.put("kbId", targetKbId);
                result.put("kbName", targetKbName);
                result.put("version", version);
                result.put("status", "success");
                results.add(result);

            } catch (IOException e) {
                log.error("上传文件失败", e);
                return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "上传文件失败");
            } catch (Exception e) {
                log.error("处理文件失败", e);
                return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "处理文件失败: " + e.getMessage());
            }
        }

        return ResultUtils.success(results);
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

    private String generateKbName(String content) {
        if (content.length() < 10) {
            return "知识库";
        }
        String trimmed = content.trim();
        if (trimmed.length() > 20) {
            trimmed = trimmed.substring(0, 20);
        }
        String[] words = trimmed.split("[\\s\\p{Punct}]+");
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (word.length() >= 2 && name.length() < 8) {
                name.append(word.charAt(0));
            }
        }
        if (name.length() == 0) {
            return "知识库";
        }
        return name.toString() + "知识库";
    }

    /**
     * 异步处理图片：提取 → 多模态描述 → 向量化存储
     * <p>不阻塞主上传流程，图片描述完成后自动可检索</p>
     *
     * @author Joseph
     */
    private void processImagesAsync(String filePath, boolean isPdf, Long kbId, String kbName,
                                     String version, String ossUrl, String originalFilename) {
        try {
            // 1. 提取图片（PDF/DOCX分别处理）
            List<ImageExtractor.ExtractedImage> images;
            if (isPdf) {
                images = imageExtractor.extractImages(filePath);
            } else {
                images = imageExtractor.extractImagesFromDocx(filePath);
            }
            if (images.isEmpty()) {
                log.info("文档无图片可处理: {}", originalFilename);
                return;
            }

            log.info("开始异步处理{}张图片: {}", images.size(), originalFilename);

            for (ImageExtractor.ExtractedImage image : images) {
                String imageOssUrl = null;
                try {
                    // 2. 上传图片到OSS（前端渲染需要HTTP URL，文件名去空格避免Markdown解析失败）
                    byte[] imageBytes = Files.readAllBytes(Paths.get(image.getPath()));
                    String safeFileName = (originalFilename + "_" + image.getName())
                            .replace(" ", "_").replace("(", "_").replace(")", "_");
                    String imageOssName = "images/" + kbId + "/" + safeFileName;
                    imageOssUrl = aliOssUtil.upload(imageBytes, imageOssName);
                    log.debug("图片已上传OSS: {}", imageOssUrl);
                } catch (Exception e) {
                    log.warn("图片上传OSS失败，将仅保存本地路径: {}", e.getMessage());
                }

                try {
                    // 3. 多模态描述（带文档上下文，生成结构化标签+描述）
                    String rawOutput = imageDescriber.describeImage(
                            image.getPath(), originalFilename, kbName);
                    if (rawOutput == null || rawOutput.isEmpty()) {
                        log.warn("图片描述为空，跳过: {}", image.getName());
                        continue;
                    }

                    // 解析结构化输出：[主题标签] ... \n[图片描述] ...
                    String searchTags;
                    String description;
                    String[] parts = rawOutput.split("\\[图片描述\\]");
                    if (parts.length >= 2) {
                        // 提取标签部分，去掉 [主题标签] 前缀
                        String tagsPart = parts[0].replace("[主题标签]", "").trim();
                        searchTags = tagsPart.isEmpty() ? rawOutput : tagsPart;
                        description = parts[1].trim();
                    } else {
                        // fallback：旧格式或解析失败，整体作为描述
                        searchTags = rawOutput;
                        description = rawOutput;
                        log.debug("图片描述未包含预期的结构化标签，使用原始输出: {}", image.getName());
                    }

                    // 构建可搜索的 embedding 文本（标签+文档上下文 → 高召回率）
                    String embeddingText = buildImageEmbeddingText(searchTags, kbName, originalFilename);
                    log.debug("图片嵌入文本: {} -> {}", image.getName(),
                            embeddingText.length() > 80 ? embeddingText.substring(0, 80) + "..." : embeddingText);

                    // 4. 向量化存储（含OSS URL）
                    // embedding用可搜索标签，LLM上下文用原始视觉描述
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", originalFilename);
                    metadata.put("kb_id", kbId);
                    metadata.put("kb_name", kbName);
                    metadata.put("version", version);
                    metadata.put("page", image.getPageNumber());
                    metadata.put("chunk_type", "IMAGE");
                    metadata.put("image_path", image.getPath());
                    if (imageOssUrl != null) {
                        metadata.put("image_url", imageOssUrl);
                    }
                    metadata.put("image_width", image.getWidth());
                    metadata.put("image_height", image.getHeight());
                    metadata.put("parent_page_text", description);
                    // 原始视觉描述存入 metadata，供 ContextBuildNode 展示给 LLM
                    metadata.put("image_description", description);
                    // 搜索标签也存一份，方便后续排查
                    metadata.put("search_tags", searchTags);

                    org.springframework.ai.document.Document aiDoc =
                            new org.springframework.ai.document.Document(embeddingText, metadata);
                    milvusV2InsertService.insertDocuments(java.util.Collections.singletonList(aiDoc));

                    log.info("图片处理完成并向量化: {}, page={}", image.getName(), image.getPageNumber());

                } catch (Exception e) {
                    log.warn("图片处理失败: {}, error={}", image.getName(), e.getMessage());
                } finally {
                    // 清理临时图片文件
                    try {
                        Files.deleteIfExists(Paths.get(image.getPath()));
                    } catch (IOException ignored) {
                    }
                }
            }

            log.info("图片异步处理全部完成: {}", originalFilename);

        } catch (Exception e) {
            log.error("图片异步处理异常: {}", originalFilename, e);
        }
    }

    /**
     * 构建图片的 embedding 文本 —— 可搜索标签 + 文档上下文
     * <p>标签来自视觉模型输出的结构化 [主题标签]，用于语义检索高召回；
     * 原始视觉描述存入 metadata.image_description，供 LLM 上下文展示。</p>
     *
     * @param searchTags      视觉模型输出的主题标签（逗号分隔的关键词）
     * @param kbName          知识库名称
     * @param originalFilename 原始文档名
     * @return 用于 embedding 的文本
     */
    private String buildImageEmbeddingText(String searchTags, String kbName, String originalFilename) {
        StringBuilder sb = new StringBuilder();
        // 文档来源（确保文档名中的关键词能被检索到）
        if (originalFilename != null && !originalFilename.isEmpty()) {
            // 去掉文件扩展名和多余符号，提取关键词
            String docKeywords = originalFilename
                    .replaceAll("\\.[^.]+$", "")         // 去扩展名
                    .replaceAll("[\\s_\\-（）()]", " ");  // 分隔符转空格
            sb.append("【").append(docKeywords).append("】");
        }
        if (kbName != null && !kbName.isEmpty()) {
            sb.append("【").append(kbName).append("】");
        }
        // 主题标签（视觉模型输出）
        if (searchTags != null && !searchTags.isEmpty()) {
            sb.append(searchTags);
        }
        return sb.toString().trim();
    }

    /**
     * 解析结果封装内部类
     * <p>
     * 用于在文档解析流程中统一传递解析结果，避免方法签名过长。
     * </p>
     *
     * @author Joseph
     */
    private static class ParsedContent {
        private final JSONObject parseResult;
        private final String contentText;

        ParsedContent(JSONObject parseResult, String contentText) {
            this.parseResult = parseResult;
            this.contentText = contentText;
        }

        JSONObject getParseResult() {
            return parseResult;
        }

        String getContentText() {
            return contentText;
        }
    }
}
