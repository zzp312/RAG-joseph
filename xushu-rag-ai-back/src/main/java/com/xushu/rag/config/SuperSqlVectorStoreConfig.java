package com.xushu.rag.config;

import com.aispace.supersql.vector.SpringVectorStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * SuperSQL 专用向量库配置
 * <p>单独创建 super_sql_store collection（无 sparse_vector），与主 RAG collection 隔离。</p>
 *
 * @author Joseph
 */
@Slf4j
@Configuration
@ConditionalOnBean(MilvusClientV2.class)
public class SuperSqlVectorStoreConfig {

    private static final String SUPER_SQL_COLLECTION = "super_sql_store";

    @Value("${spring.ai.vectorstore.milvus.embeddingDimension:1024}")
    private int embeddingDimension;

    @Value("${super-sql.init-train:false}")
    private boolean initTrain;

    @Bean
    public SpringVectorStore springVectorStore(
            MilvusServiceClient milvusClient,
            EmbeddingModel embeddingModel,
            MilvusClientV2 v2Client) {
        initSuperSqlCollection(v2Client);
        VectorStore store = MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName(SUPER_SQL_COLLECTION)
                .embeddingDimension(embeddingDimension)
                .build();
        log.info("[SuperSQL] SpringVectorStore 已绑定专用 collection: {} (dim={})", SUPER_SQL_COLLECTION, embeddingDimension);
        return new SpringVectorStore(store);
    }

    private void initSuperSqlCollection(MilvusClientV2 v2Client) {
        try {
            boolean exists = v2Client.hasCollection(HasCollectionReq.builder()
                    .collectionName(SUPER_SQL_COLLECTION).build());
            if (exists) {
                if (initTrain) {
                    v2Client.dropCollection(DropCollectionReq.builder()
                            .collectionName(SUPER_SQL_COLLECTION).build());
                    log.info("[SuperSQL] 旧 collection {} 已删除，即将重建（init-train=true）", SUPER_SQL_COLLECTION);
                } else {
                    return;
                }
            }

            var schema = CreateCollectionReq.CollectionSchema.builder()
                    .enableDynamicField(true)
                    .build();
            schema.addField(AddFieldReq.builder()
                    .fieldName("doc_id").dataType(io.milvus.v2.common.DataType.VarChar)
                    .maxLength(128).isPrimaryKey(true).autoID(false).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("content").dataType(io.milvus.v2.common.DataType.VarChar)
                    .maxLength(65535).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("metadata").dataType(io.milvus.v2.common.DataType.JSON).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("embedding").dataType(io.milvus.v2.common.DataType.FloatVector)
                    .dimension(embeddingDimension).build());

            List<IndexParam> indexes = new ArrayList<>();
            indexes.add(IndexParam.builder()
                    .fieldName("embedding")
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());

            v2Client.createCollection(CreateCollectionReq.builder()
                    .collectionName(SUPER_SQL_COLLECTION)
                    .collectionSchema(schema)
                    .indexParams(indexes)
                    .build());

            v2Client.loadCollection(LoadCollectionReq.builder()
                    .collectionName(SUPER_SQL_COLLECTION).build());

            log.info("[SuperSQL] collection {} 创建成功 (dim={})", SUPER_SQL_COLLECTION, embeddingDimension);
        } catch (Exception e) {
            log.error("[SuperSQL] collection 初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("SuperSQL collection 初始化失败", e);
        }
    }
}
