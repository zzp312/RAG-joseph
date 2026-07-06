package com.xushu.rag.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import lombok.extern.slf4j.Slf4j;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 用 V2 SDK 插入文档，避开 Spring AI V1 SDK 对 sparse_vector 字段的校验。
 * <p>只插入 doc_id / content / metadata / embedding 四个字段，
 * BM25 function 会在服务端自动填充 sparse_vector。</p>
 *
 * @author Joseph
 */
@Slf4j
@Service
public class MilvusV2InsertService {

    private final MilvusClientV2 v2Client;
    private final EmbeddingModel embeddingModel;
    private final String collectionName;

    public MilvusV2InsertService(
            MilvusClientV2 v2Client,
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.vectorstore.milvus.collectionName:vector_store_v2}") String collectionName) {
        this.v2Client = v2Client;
        this.embeddingModel = embeddingModel;
        this.collectionName = collectionName;
    }

    /**
     * 批量插入文档（调用方已做好分批，单批 <=20 条兼容 DashScope 限流）
     */
    public void insertDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;
        insertBatch(documents);
        log.info("[MilvusInsert] 插入 {} 条（embedding + BM25 auto-fill）", documents.size());
    }

    private void insertBatch(List<Document> documents) {
        Gson gson = new Gson();

        // 1. 逐条计算 embedding
        List<float[]> embeddings = new ArrayList<>();
        for (Document doc : documents) {
            float[] emb = embeddingModel.embed(doc);
            embeddings.add(emb);
        }

        if (embeddings.size() != documents.size()) {
            throw new RuntimeException(String.format(
                    "embedding 数量(%d) ≠ 文档数量(%d)", embeddings.size(), documents.size()));
        }

        // 2. 构造 JsonObject 列表（V2 SDK 强制要求 Gson JsonObject）
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] vec = embeddings.get(i);

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", doc.getId());
            row.addProperty("content", doc.getText());
            // metadata: Map → JsonElement
            row.add("metadata", gson.toJsonTree(doc.getMetadata()));
            // embedding: float[] → List<Float> → JsonElement
            List<Float> embList = new ArrayList<>(vec.length);
            for (float v : vec) embList.add(v);
            row.add("embedding", gson.toJsonTree(embList));
            // 不传 sparse_vector — BM25 function 服务端自动填充

            rows.add(row);
        }

        // 3. V2 SDK 插入（不传 sparse_vector，BM25 function 自动填充）
        v2Client.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());

        log.debug("[MilvusInsert] done, count={}", rows.size());
    }
}
