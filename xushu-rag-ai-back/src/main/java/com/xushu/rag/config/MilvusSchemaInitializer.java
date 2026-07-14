package com.xushu.rag.config;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Milvus Collection Schema 初始化器（V2 SDK，含 BM25 + 中文分词器）。
 * <p>删除旧 collection 后重启即可自动创建新 schema。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class MilvusSchemaInitializer {

    @Value("${spring.ai.vectorstore.milvus.collectionName:vector_store_v2}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.milvus.embeddingDimension:1024}")
    private int embeddingDimension;

    @Value("${spring.ai.vectorstore.milvus.indexType:IVF_FLAT}")
    private String indexType;

    @Value("${spring.ai.vectorstore.milvus.metricType:COSINE}")
    private String metricType;

    private final MilvusClientV2 v2Client;

    public MilvusSchemaInitializer(MilvusClientV2 v2Client) {
        this.v2Client = v2Client;
    }

    @PostConstruct
    public void initCollection() {
        log.info("[MilvusSchema] 初始化 collection: {}, 维度: {}d (V2 SDK + BM25 + 中文分词)",
                collectionName, embeddingDimension);

        try {
            boolean exists = v2Client.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());

            if (exists) {
                log.info("[MilvusSchema] collection {} 已存在，跳过创建", collectionName);
                log.info("[MilvusSchema] 如需重建，请先删掉旧 collection 后重启");
                return;
            }

            createCollection();
            loadCollection();

            log.info("[MilvusSchema] collection {} 初始化完成（BM25 + chinese 分词器）", collectionName);
        } catch (Exception e) {
            log.error("[MilvusSchema] 初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("Milvus collection 初始化失败", e);
        }
    }

    private void createCollection() {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .build();

        schema.addField(AddFieldReq.builder()
                .fieldName("doc_id")
                .dataType(io.milvus.v2.common.DataType.VarChar)
                .maxLength(128)
                .isPrimaryKey(true)
                .autoID(false)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(io.milvus.v2.common.DataType.VarChar)
                .maxLength(65535)
                .enableAnalyzer(true)
                .analyzerParams(Map.of("type", "chinese"))
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("metadata")
                .dataType(io.milvus.v2.common.DataType.JSON)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(io.milvus.v2.common.DataType.FloatVector)
                .dimension(embeddingDimension)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("sparse_vector")
                .dataType(io.milvus.v2.common.DataType.SparseFloatVector)
                .build());

        CreateCollectionReq.Function function = CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name("bm25_udf")
                .inputFieldNames(Collections.singletonList("content"))
                .outputFieldNames(Collections.singletonList("sparse_vector"))
                .build();
        schema.addFunction(function);

        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("embedding")
                .indexType("IVF_FLAT".equalsIgnoreCase(indexType) ? IndexParam.IndexType.IVF_FLAT : IndexParam.IndexType.AUTOINDEX)
                .metricType("COSINE".equalsIgnoreCase(metricType) ? IndexParam.MetricType.COSINE : IndexParam.MetricType.IP)
                .build());
        indexes.add(IndexParam.builder()
                .fieldName("sparse_vector")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description("RAG向量库 (" + embeddingDimension + "d) + BM25(chinese)")
                .collectionSchema(schema)
                .indexParams(indexes)
                .build();

        v2Client.createCollection(createReq);
        log.info("[MilvusSchema] collection {} 创建成功（BM25 + chinese 分词）", collectionName);
    }

    private void loadCollection() {
        v2Client.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("[MilvusSchema] collection {} 已加载", collectionName);
    }
}
