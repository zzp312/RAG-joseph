package com.xushu.rag.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.entity.DocumentPage;
import com.xushu.rag.entity.KnowledgeBase;
import com.xushu.rag.pojo.dto.UploadResult;
import com.xushu.rag.service.impl.DocumentPageServiceImpl;
import com.xushu.rag.utils.AliOssUtil;
import com.xushu.rag.utils.ImageDescriber;
import com.xushu.rag.utils.ImageExtractor;
import com.xushu.rag.utils.JavaDocumentParser;
import com.xushu.rag.utils.PythonScriptExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库文件上传服务
 * <p>从 KnowledgeController.uploadWithKb 抽取的核心上传逻辑，
 * 供 Controller 和 MCP upload_kb_file 工具复用。</p>
 *
 * @author Joseph
 */
@Slf4j
@Service
public class KnowledgeUploadService {

    @Autowired
    private AliOssUtil aliOssUtil;

    @Autowired
    private TokenTextSplitter tokenTextSplitter;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentPageService documentPageService;

    @Autowired
    private MilvusV2InsertService milvusV2InsertService;

    @Autowired
    private PythonScriptExecutor pythonScriptExecutor;

    @Autowired
    private JavaDocumentParser javaDocumentParser;

    @Autowired
    private ImageExtractor imageExtractor;

    @Autowired
    private ImageDescriber imageDescriber;

    /**
     * 上传单个文件到知识库（核心方法）
     *
     * @param file         文件
     * @param kbId         目标知识库ID（可选，autoClassify=true时可为null）
     * @param kbName       目标知识库名称（可选）
     * @param autoClassify 是否自动分类
     * @param chunkSize    切片大小
     * @param chunkOverlap 切片重叠
     * @return 上传结果
     */
    public UploadResult uploadFile(MultipartFile file, Long kbId, String kbName,
                                   Boolean autoClassify, Integer chunkSize, Integer chunkOverlap) {
        String originalFilename = file.getOriginalFilename();

        try {
            String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            String baseName = originalFilename.substring(0, originalFilename.lastIndexOf("."));
            String ossVersion = "v1.0." + System.currentTimeMillis();
            String objectName = baseName + "_" + ossVersion + extension;
            String url = aliOssUtil.upload(file.getBytes(), objectName);

            Path tempFilePath = saveTempFile(file);
            String tempPathStr = tempFilePath.toString();

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

                Map<Integer, StringBuilder> pageFullTexts = new LinkedHashMap<>();
                for (int i = 0; i < chunks.size(); i++) {
                    JSONObject c = chunks.getJSONObject(i);
                    Integer p = c.getInteger("page");
                    if (p != null) {
                        pageFullTexts.computeIfAbsent(p, k -> new StringBuilder())
                                .append(c.getString("text")).append("\n");
                    }
                }
                List<DocumentPage> pageEntities = new ArrayList<>();
                for (Map.Entry<Integer, StringBuilder> entry : pageFullTexts.entrySet()) {
                    String fullText = entry.getValue().toString().trim();
                    pageEntities.addAll(DocumentPageServiceImpl.splitPage(
                            originalFilename, version, entry.getKey(), fullText, 8000, 200));
                }
                documentPageService.batchSave(pageEntities);

                for (int i = 0; i < chunks.size(); i++) {
                    JSONObject chunk = chunks.getJSONObject(i);
                    Integer page = chunk.getInteger("page");
                    String chunkType = chunk.getString("type");
                    String chunkTypeUpper = chunkType != null ? chunkType.toUpperCase() : "TEXT";

                    String pageText = chunk.getString("text");
                    List<org.springframework.ai.document.Document> splitDocs;
                    if ("TEXT".equalsIgnoreCase(chunkTypeUpper)) {
                        try {
                            splitDocs = tokenTextSplitter.apply(
                                    Collections.singletonList(
                                            new org.springframework.ai.document.Document(pageText)));
                        } catch (Exception e) {
                            log.warn("TokenTextSplitter拆分失败，保留整块: page={}", page);
                            splitDocs = Collections.singletonList(
                                    new org.springframework.ai.document.Document(pageText));
                        }
                    } else {
                        String safe = pageText.length() > 1500
                                ? pageText.substring(0, 1500) : pageText;
                        splitDocs = Collections.singletonList(
                                new org.springframework.ai.document.Document(safe));
                    }

                    log.info("[Chunk切分] source={}, page={}, type={}, 原文{}字 → 切为{}个chunk",
                            originalFilename, page, chunkTypeUpper,
                            pageText.length(), splitDocs.size());

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
                        aiDocuments.add(new org.springframework.ai.document.Document(
                                splitDoc.getText(), metadata));
                    }
                }
            } else {
                Resource resource = file.getResource();
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<org.springframework.ai.document.Document> originalDocs = reader.read();
                for (org.springframework.ai.document.Document doc : originalDocs) {
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
                    metadata.put("chunk_type", "TEXT");
                    aiDocuments.add(new org.springframework.ai.document.Document(doc.getText(), metadata));
                }
                aiDocuments = tokenTextSplitter.apply(aiDocuments);
            }

            final int EMBED_BATCH_SIZE = 20;
            for (int batch = 0; batch < aiDocuments.size(); batch += EMBED_BATCH_SIZE) {
                int toIdx = Math.min(batch + EMBED_BATCH_SIZE, aiDocuments.size());
                milvusV2InsertService.insertDocuments(aiDocuments.subList(batch, toIdx));
            }
            log.info("向量化完成，文件: {}，共{}个分块", originalFilename, aiDocuments.size());

            String vectorIds = JSON.toJSONString(aiDocuments.stream()
                    .map(org.springframework.ai.document.Document::getId)
                    .collect(Collectors.toList()));

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
                final boolean isPdf = isPdfFile;
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

            return UploadResult.builder()
                    .fileName(originalFilename)
                    .kbId(targetKbId)
                    .kbName(targetKbName)
                    .version(version)
                    .status("success")
                    .chunkCount(aiDocuments.size())
                    .build();

        } catch (Exception e) {
            log.error("处理文件失败: {}", originalFilename, e);
            return UploadResult.builder()
                    .fileName(originalFilename)
                    .kbId(kbId)
                    .kbName(kbName)
                    .status("failed")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 上传多个文件到知识库
     */
    public List<UploadResult> uploadFiles(List<MultipartFile> files, Long kbId, String kbName,
                                          Boolean autoClassify, Integer chunkSize, Integer chunkOverlap) {
        List<UploadResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(uploadFile(file, kbId, kbName, autoClassify, chunkSize, chunkOverlap));
        }
        return results;
    }

    // ========== 私有方法（从 KnowledgeController 抽取） ==========

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
                log.info("使用JavaDocumentParser解析: {}", originalFilename);
                parseResult = javaDocumentParser.parse(tempPathStr);
                if (parseResult.containsKey("error")) {
                    throw new Exception(parseResult.getString("error"));
                }
                contentText = parseResult.getString("text");
            } else {
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

    private void processImagesAsync(String filePath, boolean isPdf, Long kbId, String kbName,
                                     String version, String ossUrl, String originalFilename) {
        try {
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
                    String rawOutput = imageDescriber.describeImage(
                            image.getPath(), originalFilename, kbName);
                    if (rawOutput == null || rawOutput.isEmpty()) {
                        log.warn("图片描述为空，跳过: {}", image.getName());
                        continue;
                    }

                    String searchTags;
                    String description;
                    String[] parts = rawOutput.split("\\[图片描述\\]");
                    if (parts.length >= 2) {
                        String tagsPart = parts[0].replace("[主题标签]", "").trim();
                        searchTags = tagsPart.isEmpty() ? rawOutput : tagsPart;
                        description = parts[1].trim();
                    } else {
                        searchTags = rawOutput;
                        description = rawOutput;
                        log.debug("图片描述未包含预期的结构化标签，使用原始输出: {}", image.getName());
                    }

                    String embeddingText = buildImageEmbeddingText(searchTags, kbName, originalFilename);

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
                    metadata.put("image_description", description);
                    metadata.put("search_tags", searchTags);

                    org.springframework.ai.document.Document aiDoc =
                            new org.springframework.ai.document.Document(embeddingText, metadata);
                    milvusV2InsertService.insertDocuments(Collections.singletonList(aiDoc));

                    log.info("图片处理完成并向量化: {}, page={}", image.getName(), image.getPageNumber());

                } catch (Exception e) {
                    log.warn("图片处理失败: {}, error={}", image.getName(), e.getMessage());
                } finally {
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

    private String buildImageEmbeddingText(String searchTags, String kbName, String originalFilename) {
        StringBuilder sb = new StringBuilder();
        if (originalFilename != null && !originalFilename.isEmpty()) {
            String docKeywords = originalFilename
                    .replaceAll("\\.[^.]+$", "")
                    .replaceAll("[\\s_\\-（）()]", " ");
            sb.append("【").append(docKeywords).append("】");
        }
        if (kbName != null && !kbName.isEmpty()) {
            sb.append("【").append(kbName).append("】");
        }
        if (searchTags != null && !searchTags.isEmpty()) {
            sb.append(searchTags);
        }
        return sb.toString().trim();
    }

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
