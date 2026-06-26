package com.xushu.rag.controller;

import com.alibaba.fastjson.JSON;
import com.xushu.rag.advisors.MetadataAwareQuestionAnswerAdvisor;
import com.xushu.rag.annotation.Loggable;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.context.BaseContext;
import com.xushu.rag.entity.SensitiveWord;
import com.xushu.rag.service.DocumentPageService;
import com.xushu.rag.service.IRerankStrategy;
import com.xushu.rag.service.PromptTemplateService;
import com.xushu.rag.service.SensitiveWordService;
import com.xushu.rag.tools.RagTool;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.*;
import java.util.LinkedHashSet;

@Tag(name = "AiRagController", description = "Rag接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/ai")
public class AiRagController {

    ChatClient chatClient;
    VectorStore vectorStore;

    @Autowired
    private SensitiveWordService sensitiveWordService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    /**
     * 重排序策略（策略模式），默认按版本优先
     * Phase 2 替换为 LLMRerankStrategy 实现语义重排，零代码侵入
     *
     * @author Joseph
     */
    @Autowired
    private IRerankStrategy rerankStrategy;

    /**
     * 页面原文服务（Small-to-Big回表查询MySQL document_pages表）
     *
     * @author Joseph
     */
    @Autowired
    private DocumentPageService documentPageService;

    private ChatModel chatModel;

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是"Joseph.zhou"知识库系统的对话助手，请以乐于助人的方式进行对话，
            {rag_message}
            今天的日期：{current_data}
            """;

    public AiRagController(ChatModel chatModel, ChatMemory chatMemory,
                           VectorStore vectorStore,
                           RagTool ragTool) {
        this.chatModel = chatModel;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(DEFAULT_SYSTEM_PROMPT)
                .defaultSystem(p -> p.param("rag_message", ""))
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(chatMemory).build(),
                        SimpleLoggerAdvisor.builder().build(),
                        new MetadataAwareQuestionAnswerAdvisor()
                )
                .defaultTools(ragTool)
                .build();
        this.vectorStore = vectorStore;
    }

    @Operation(summary = "rag post", description = "Rag对话接口POST版本（旧版，兼容原有逻辑）")
    @PostMapping(value = "/rag")
    @Loggable
    public Flux<String> generatePost(@RequestParam(value = "sources", required = false) List<String> sources,
                                     @RequestParam(value = "message", defaultValue = "你好") String message) {

        for (SensitiveWord sensitiveWord : sensitiveWordService.list()) {
            if (message.contains(sensitiveWord.getWord())) {
                return Flux.just("包含敏感词:" + sensitiveWord.getWord());
            }
        }

        return processNormalRagQuery(sources, message, null);
    }

    @Operation(summary = "rag with kb", description = "Rag对话接口（支持知识库过滤和版本优先级）")
    @PostMapping(value = "/rag-with-kb")
    @Loggable
    public Flux<String> generateWithKb(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "kbId", required = false) Long kbId,
            @RequestParam(value = "kbIds", required = false) List<Long> kbIds,
            @RequestParam(value = "sources", required = false) List<String> sources) {

        for (SensitiveWord sensitiveWord : sensitiveWordService.list()) {
            if (message.contains(sensitiveWord.getWord())) {
                return Flux.just("包含敏感词:" + sensitiveWord.getWord());
            }
        }

        List<Long> targetKbIds = new ArrayList<>();
        if (kbId != null) {
            targetKbIds.add(kbId);
        }
        if (kbIds != null && !kbIds.isEmpty()) {
            targetKbIds.addAll(kbIds);
        }

        Long effectiveKbId = targetKbIds.isEmpty() ? null : targetKbIds.get(0);

        return processKbRagQuery(sources, message, targetKbIds, effectiveKbId);
    }

    private Flux<String> processNormalRagQuery(List<String> sources, String message, Long kbId) {
        Long userId = BaseContext.getCurrentId();
        ChatClient.ChatClientRequestSpec clientRequestSpec = chatClient.prompt()
                .user(message)
                .system(a -> a.param("current_data", LocalDate.now().toString()))
                .advisors(a -> a.param("userMessage", message))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId)
                        .param("chat_memory_response_size", 16));

        if (sources != null && !sources.isEmpty()) {
            SearchRequest.Builder searchRequestBuilder = SearchRequest.builder()
                    .query(message)
                    .similarityThreshold(0.1d)
                    .topK(10)
                    .filterExpression("source in " + JSON.toJSONString(sources));

            if (kbId != null) {
                searchRequestBuilder.filterExpression("kb_id == " + kbId + " && source in " + JSON.toJSONString(sources));
            }

            clientRequestSpec = clientRequestSpec
                    .system(a -> a.param("rag_message", """
                            如果涉及RAG，请提供文件来源，我会提供给你文件来源，
                            请严格基于知识库内容回答用户问题，
                            不要添加任何知识库之外的信息。如果知识库内容不完整，仅需基于已有信息作答，
                            不要自行补充。
                            """))
                    .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(searchRequestBuilder.build())
                            .build());
        }

        return clientRequestSpec.stream().content();
    }

    private Flux<String> processKbRagQuery(List<String> sources, String message, List<Long> kbIds, Long effectiveKbId) {
        Long userId = BaseContext.getCurrentId();

        boolean hasFilter = (kbIds != null && !kbIds.isEmpty()) || (sources != null && !sources.isEmpty());
        String ragMessage = buildRagMessageWithTemplate(effectiveKbId, message, hasFilter);

        ChatClient.ChatClientRequestSpec clientRequestSpec = chatClient.prompt()
                .user(message)
                .system(a -> a.param("current_data", LocalDate.now().toString()))
                .system(a -> a.param("rag_message", ragMessage))
                .advisors(a -> a.param("userMessage", message))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId)
                        .param("chat_memory_response_size", 16));

        SearchRequest.Builder searchRequestBuilder = SearchRequest.builder()
                .query(message)
                .similarityThreshold(0.1d)
                .topK(10);

        StringBuilder filterExpr = new StringBuilder();
        List<String> filterParts = new ArrayList<>();

        if (kbIds != null && !kbIds.isEmpty()) {
            String kbFilter = "kb_id in " + JSON.toJSONString(kbIds);
            filterParts.add(kbFilter);
        }

        if (sources != null && !sources.isEmpty()) {
            String sourceFilter = "source in " + JSON.toJSONString(sources);
            filterParts.add(sourceFilter);
        }

        log.info("[检索过滤] kbIds={}, sources={}, filterExpr={}",
                kbIds, sources, filterParts.isEmpty() ? "无过滤(全文件)" : String.join(" && ", filterParts));

        if (!filterParts.isEmpty()) {
            filterExpr.append(String.join(" && ", filterParts));
            searchRequestBuilder.filterExpression(filterExpr.toString());
        }

        List<Document> retrievedDocs;
        try {
            retrievedDocs = vectorStore.similaritySearch(searchRequestBuilder.build());
        } catch (Exception e) {
            log.error("向量检索失败，Milvus可能未加载collection或连接异常: {}", e.getMessage());
            // 无检索结果时直接返回AI自由回答，不中断对话
            retrievedDocs = Collections.emptyList();
        }

        // 策略模式重排序（当前：版本优先；Phase 2：LLM语义重排）
        Map<String, Object> rerankContext = new HashMap<>();
        rerankContext.put("query", message);
        rerankContext.put("kbIds", kbIds);
        List<Document> rankedDocs = rerankStrategy.rerank(retrievedDocs, rerankContext);

        if (!rankedDocs.isEmpty()) {
            // 小→大：按page去重，映射到父页面全文
            List<Document> parentDocs = mapToParentPages(rankedDocs);
            String context = buildParentContext(parentDocs);

            clientRequestSpec = clientRequestSpec
                    .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(searchRequestBuilder.build())
                            .build());
        }

        return clientRequestSpec.stream().content();
    }

    /**
     * 将chunk检索结果映射到父页面（Small-to-Big: MySQL回表查原文）
     * <p>
     * 参照RAG-Challenge-2的return_parent_pages模式：
     * chunks只存page指针 → 检索时批量回表document_pages取完整页面原文
     * </p>
     *
     * @author Joseph
     */
    private List<Document> mapToParentPages(List<Document> chunks) {
        // 1. 收集所有(source+version, page)组合
        Map<String, List<Integer>> svPagesMap = new LinkedHashMap<>();
        for (Document chunk : chunks) {
            Object sourceObj = chunk.getMetadata().get("source");
            Object versionObj = chunk.getMetadata().get("version");
            Object pageObj = chunk.getMetadata().get("page");
            if (sourceObj == null || pageObj == null) continue;
            String svKey = sourceObj + "|" + (versionObj != null ? versionObj : "");
            int page = pageObj instanceof Integer ? (Integer) pageObj
                    : (int) Double.parseDouble(pageObj.toString());
            svPagesMap.computeIfAbsent(svKey, k -> new ArrayList<>()).add(page);
        }

        // 2. 按(source,version)回表MySQL查原文
        Map<String, Document> pageMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : svPagesMap.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String source = parts[0];
            String version = parts[1];
            List<Integer> pageNums = new ArrayList<>(new LinkedHashSet<>(entry.getValue()));

            log.info("[Small-to-Big召回] chunk命中 source={}, version={}, pages={}",
                    source, version, pageNums);
            log.info("[召回上下文] 共{}个chunk → 去重{}个父页面",
                    chunks.size(), svPagesMap.size());
            Map<String, String> pageTexts = documentPageService.getPageTexts(source, version, pageNums);

            for (Integer pn : pageNums) {
                String key = source + "_" + pn;
                if (pageMap.containsKey(key)) continue;
                String fullText = pageTexts.get(key);
                if (fullText == null || fullText.isEmpty()) continue;

                Map<String, Object> pageMeta = new HashMap<>();
                pageMeta.put("source", source);
                pageMeta.put("page", pn);
                pageMeta.put("version", version);
                pageMeta.put("chunk_type", "PARENT_PAGE");
                for (Document chunk : chunks) {
                    if (source.equals(chunk.getMetadata().get("source"))
                            && pn.equals(chunk.getMetadata().get("page"))) {
                        if (chunk.getMetadata().get("kb_id") != null)
                            pageMeta.put("kb_id", chunk.getMetadata().get("kb_id"));
                        if (chunk.getMetadata().get("kb_name") != null)
                            pageMeta.put("kb_name", chunk.getMetadata().get("kb_name"));
                        break;
                    }
                }
                pageMap.put(key, new Document(truncatePageText(fullText), pageMeta));
            }
        }

        // 3. 无页码的chunk直接保留
        for (Document chunk : chunks) {
            if (chunk.getMetadata().get("page") == null) {
                pageMap.putIfAbsent("nopage_" + chunk.hashCode(), chunk);
            }
        }

        return new ArrayList<>(pageMap.values());
    }

    /**
     * 构建父页面级上下文
     *
     * @param parentDocs 去重后的父页面文档列表
     * @return 格式化的上下文字符串
     * @author Joseph
     */
    private String buildParentContext(List<Document> parentDocs) {
        StringBuilder context = new StringBuilder();
        Set<String> addedSources = new HashSet<>();

        for (Document doc : parentDocs) {
            String source = doc.getMetadata().get("source").toString();
            String version = (String) doc.getMetadata().getOrDefault("version", "");
            String kbName = (String) doc.getMetadata().getOrDefault("kb_name", "");
            Object pageObj = doc.getMetadata().get("page");
            String chunkType = (String) doc.getMetadata().getOrDefault("chunk_type", "");

            boolean isOldVersion = addedSources.contains(source);

            if (context.length() > 0) {
                context.append("\n\n");
            }

            if (isOldVersion) {
                context.append("[旧版本文档] ");
            }

            context.append("来源：").append(kbName).append("/").append(source);
            if (pageObj != null) {
                context.append(" (第").append(pageObj).append("页)");
            }
            if (!version.isEmpty()) {
                context.append(" 版本：").append(version);
            }
            if (!chunkType.isEmpty()) {
                context.append(" [").append(chunkType).append("]");
            }
            // 图片类型：Markdown图片语法，前端可直接渲染（URL空格编码避免解析失败）
            if ("IMAGE".equalsIgnoreCase(chunkType)) {
                Object imgUrl = doc.getMetadata().get("image_url");
                if (imgUrl != null && !imgUrl.toString().isEmpty()) {
                    String safeUrl = imgUrl.toString().replace(" ", "%20");
                    context.append("\n![图片](").append(safeUrl).append(")");
                }
            }
            context.append("\n");
            context.append(doc.getText());

            addedSources.add(source);
        }

        return context.toString();
    }

    /**
     * 页面文本长度截断，超出8000字符时保留段落边界
     *
     * @param text 原始文本
     * @return 截断后的文本
     * @author Joseph
     */
    private String truncatePageText(String text) {
        final int MAX_LENGTH = 8000;
        if (text == null || text.length() <= MAX_LENGTH) {
            return text != null ? text : "";
        }

        // 在8000字符附近寻找最近的段落边界
        String truncated = text.substring(0, MAX_LENGTH);
        int lastNewline = truncated.lastIndexOf("\n\n");
        if (lastNewline > MAX_LENGTH / 2) {
            truncated = truncated.substring(0, lastNewline);
        }
        return truncated + "\n（内容已截断）";
    }

    private String buildRagMessageWithTemplate(Long kbId, String question, boolean hasFilter) {
        String template = promptTemplateService.renderTemplate(kbId, question, "{context}");

        String baseRule = hasFilter
                ? """
                你已限定在指定的知识库/文件范围内检索，请严格遵守以下规则：
                1. 只能使用下方【检索上下文】中提供的信息作答，禁止引用对话历史或外部知识。
                2. 如果检索上下文为空或与问题无关，直接回答"知识库中暂无相关内容"，
                   不要猜测、不要根据历史对话补充、不要使用你自己的知识。
                """
                : """
                请严格基于知识库内容回答用户问题，不要添加任何知识库之外的信息。
                如果知识库内容不完整，仅需基于已有信息作答，不要自行补充。
                """;

        return template.replace("{context}", baseRule + """

                表格生成规范（如涉及表格内容，必须严格遵守）：
                - 表格前必须有一个空行，表头行、分隔行、数据行各自独立成行。
                - 格式示例：
                  | 列名1 | 列名2 | 列名3 |
                  |-------|-------|-------|
                  | 值1   | 值2   | 值3   |
                - 禁止将标题文字与表格管道符 | 放在同一行，禁止分隔行与数据行合并。

                图片展示规则：
                - 如果上下文中包含 Markdown 格式的图片 ![](url)，直接原样引用来展示图片。
                - 每张图片下方标注来源文档和页码。

                注意：以下内容可能包含不同版本的文档，请优先参考新版本内容。
                """);
    }

}
