package com.xushu.rag.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.common.ResultUtils;
import com.xushu.rag.entity.AliOssFile;
import com.xushu.rag.entity.KnowledgeBase;
import com.xushu.rag.pojo.dto.QueryFileDTO;
import com.xushu.rag.service.AliOssFileService;
import com.xushu.rag.service.DocumentService;
import com.xushu.rag.service.KnowledgeBaseService;
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
                vectorStore.add(splitDocuments);
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
                    parseResult = new JSONObject();
                    parseResult.put("text", contentText);
                }

                Long targetKbId = kbId;
                String targetKbName = kbName;

                if (autoClassify && targetKbId == null) {
                    BaseResponse suggestResponse = knowledgeBaseService.suggestKnowledgeBase(contentText);
                    if (suggestResponse.getCode() == 0) {
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
                if (parseResult.containsKey("chunks")) {
                    JSONArray chunks = parseResult.getJSONArray("chunks");
                    for (int i = 0; i < chunks.size(); i++) {
                        JSONObject chunk = chunks.getJSONObject(i);
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("source", originalFilename);
                        metadata.put("kb_id", targetKbId);
                        metadata.put("kb_name", targetKbName);
                        metadata.put("version", version);
                        // 过滤null值，Spring AI Document不允许metadata中有null
                        Integer page = chunk.getInteger("page");
                        if (page != null) {
                            metadata.put("page", page);
                        }
                        String type = chunk.getString("type");
                        if (type != null) {
                            metadata.put("type", type);
                        }
                        org.springframework.ai.document.Document aiDoc = new org.springframework.ai.document.Document(chunk.getString("text"), metadata);
                        aiDocuments.add(aiDoc);
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
                        aiDocuments.add(new org.springframework.ai.document.Document(doc.getText(), metadata));
                    }
                    aiDocuments = tokenTextSplitter.apply(aiDocuments);
                }

                vectorStore.add(aiDocuments);
                log.info("向量化完成，文件: {}", originalFilename);

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

                Files.deleteIfExists(tempFilePath);

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
}
