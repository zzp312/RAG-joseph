package com.xushu.rag.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus V2 SDK 客户端配置，供插入和检索共享使用。
 *
 * @author Joseph
 */
@Slf4j
@Configuration
public class MilvusV2ClientConfig {

    @Value("${spring.ai.vectorstore.milvus.client.host:192.168.1.144}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2() {
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .dbName(databaseName)
                .build());
        log.info("[MilvusV2Config] V2 客户端已连接: {}:{}/{}", milvusHost, milvusPort, databaseName);
        return client;
    }
}
