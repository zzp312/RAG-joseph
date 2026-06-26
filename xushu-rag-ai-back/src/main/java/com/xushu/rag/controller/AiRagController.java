package com.xushu.rag.controller;

import com.alibaba.fastjson.JSON;
import com.xushu.rag.advisors.MetadataAwareQuestionAnswerAdvisor;
import com.xushu.rag.annotation.Loggable;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.context.BaseContext;
import com.xushu.rag.entity.SensitiveWord;
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
import java.util.stream.Collectors;

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

    private ChatModel chatModel;

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是"XS"知识库系统的对话助手，请以乐于助人的方式进行对话，
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
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId));

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

        String ragMessage = buildRagMessageWithTemplate(effectiveKbId, message);

        ChatClient.ChatClientRequestSpec clientRequestSpec = chatClient.prompt()
                .user(message)
                .system(a -> a.param("current_data", LocalDate.now().toString()))
                .system(a -> a.param("rag_message", ragMessage))
                .advisors(a -> a.param("userMessage", message))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId));

        SearchRequest.Builder searchRequestBuilder = SearchRequest.builder()
                .query(message)
                .similarityThreshold(0.1d)
                .topK(15);

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

        if (!filterParts.isEmpty()) {
            filterExpr.append(String.join(" && ", filterParts));
            searchRequestBuilder.filterExpression(filterExpr.toString());
        }

        List<Document> retrievedDocs = vectorStore.similaritySearch(searchRequestBuilder.build());
        List<Document> rankedDocs = rankByVersion(retrievedDocs);

        if (!rankedDocs.isEmpty()) {
            String context = buildContextWithVersionInfo(rankedDocs);

            clientRequestSpec = clientRequestSpec
                    .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(searchRequestBuilder.build())
                            .build());
        }

        return clientRequestSpec.stream().content();
    }

    private String buildRagMessageWithTemplate(Long kbId, String question) {
        String template = promptTemplateService.renderTemplate(kbId, question, "{context}");

        return template.replace("{context}", """
                如果涉及RAG，请提供文件来源，我会提供给你文件来源，
                请严格基于知识库内容回答用户问题，
                不要添加任何知识库之外的信息。如果知识库内容不完整，仅需基于已有信息作答，
                不要自行补充。
                
                注意：以下内容可能包含不同版本的文档，请优先参考新版本内容。
                """);
    }

    private List<Document> rankByVersion(List<Document> documents) {
        Map<String, List<Document>> docsBySource = documents.stream()
                .collect(Collectors.groupingBy(doc -> doc.getMetadata().get("source").toString()));

        List<Document> rankedDocs = new ArrayList<>();

        for (Map.Entry<String, List<Document>> entry : docsBySource.entrySet()) {
            List<Document> sourceDocs = entry.getValue();

            sourceDocs.sort((doc1, doc2) -> {
                String version1 = (String) doc1.getMetadata().getOrDefault("version", "");
                String version2 = (String) doc2.getMetadata().getOrDefault("version", "");

                return compareVersions(version2, version1);
            });

            rankedDocs.addAll(sourceDocs);
        }

        rankedDocs.sort((doc1, doc2) -> {
            String version1 = (String) doc1.getMetadata().getOrDefault("version", "");
            String version2 = (String) doc2.getMetadata().getOrDefault("version", "");
            return compareVersions(version2, version1);
        });

        return rankedDocs;
    }

    private int compareVersions(String v1, String v2) {
        if (v1.equals(v2)) return 0;

        try {
            String[] parts1 = v1.replace("v", "").split("\\.");
            String[] parts2 = v2.replace("v", "").split("\\.");

            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                long num1 = i < parts1.length ? Long.parseLong(parts1[i]) : 0;
                long num2 = i < parts2.length ? Long.parseLong(parts2[i]) : 0;
                if (num1 != num2) {
                    return Long.compare(num1, num2);
                }
            }
        } catch (Exception e) {
            log.warn("版本号比较失败: {} vs {}", v1, v2);
        }

        return v1.compareTo(v2);
    }

    private String buildContextWithVersionInfo(List<Document> documents) {
        StringBuilder context = new StringBuilder();
        Set<String> addedSources = new HashSet<>();

        for (Document doc : documents) {
            String source = doc.getMetadata().get("source").toString();
            String version = (String) doc.getMetadata().getOrDefault("version", "");
            String kbName = (String) doc.getMetadata().getOrDefault("kb_name", "");

            boolean isOldVersion = addedSources.contains(source);

            if (context.length() > 0) {
                context.append("\n\n");
            }

            if (isOldVersion) {
                context.append("[旧版本文档] ");
            }

            context.append("来源：").append(kbName).append("/").append(source);
            if (!version.isEmpty()) {
                context.append(" (版本：").append(version).append(")");
            }
            context.append("\n");
            context.append(doc.getText());

            addedSources.add(source);
        }

        return context.toString();
    }
}
