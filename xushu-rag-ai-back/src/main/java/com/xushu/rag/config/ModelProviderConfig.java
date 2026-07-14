package com.xushu.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型 provider 切换配置。
 * <p>通过 {@code xushu.ai.provider} 在 DashScope（默认，云端百炼）与 Ollama（本地模型）之间切换。</p>
 * <p>为避免 DashScope / Ollama 两个 auto-config 同时注册 ChatModel/EmbeddingModel 导致 Spring 注入歧义，
 * 本配置为 <b>当前 provider</b> 的 Bean 标注 @Primary，确保全链路（Graph Agent、意图分类、检索等）
 * 统一注入正确的模型实现。</p>
 * <p>VectorStore 无需在此处理——MilvusVectorStoreAutoConfiguration 会自动注入当前 @Primary 的
 * EmbeddingModel，只要两个 provider 的 embedding 维度一致（均为 1024），即可复用同一 collection。</p>
 *
 * @author Joseph
 */
@Slf4j
@Configuration
public class ModelProviderConfig {

    // ========== DashScope（默认 provider）==========

    @Bean
    @Primary
    @ConditionalOnProperty(name = "xushu.ai.provider", havingValue = "dashscope", matchIfMissing = true)
    public ChatModel primaryDashScopeChatModel(
            @Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
        log.info("[ModelProvider] 使用 DashScope ChatModel（@Primary）");
        return dashScopeChatModel;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "xushu.ai.provider", havingValue = "dashscope", matchIfMissing = true)
    public EmbeddingModel primaryDashScopeEmbeddingModel(
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel dashScopeEmbeddingModel) {
        log.info("[ModelProvider] 使用 DashScope EmbeddingModel（@Primary）");
        return dashScopeEmbeddingModel;
    }

    // ========== Ollama provider ==========

    @Bean
    @Primary
    @ConditionalOnProperty(name = "xushu.ai.provider", havingValue = "ollama")
    public ChatModel ollamaPrimaryChatModel(
            @Value("${xushu.ai.ollama.base-url}") String baseUrl,
            @Value("${xushu.ai.ollama.chat.model}") String chatModel) {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();
        log.info("[ModelProvider] 使用 Ollama ChatModel: baseUrl={}, model={}", baseUrl, chatModel);
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaChatOptions.builder().model(chatModel).build())
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "xushu.ai.provider", havingValue = "ollama")
    public EmbeddingModel ollamaPrimaryEmbeddingModel(
            @Value("${xushu.ai.ollama.base-url}") String baseUrl,
            @Value("${xushu.ai.ollama.embedding.model}") String embeddingModel) {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();
        log.info("[ModelProvider] 使用 Ollama EmbeddingModel: baseUrl={}, model={}", baseUrl, embeddingModel);
        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaEmbeddingOptions.builder().model(embeddingModel).build())
                .build();
    }
}
