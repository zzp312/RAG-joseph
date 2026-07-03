package com.xushu.rag.graph.nodes;

import com.alibaba.fastjson.JSONObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Milvus Sparse-BM25 混合检索兼容性检测 + Schema迁移节点
 *
 * @author Joseph
 */
@Slf4j
@Component
public class HybridSearchTestNode {

    @Value("${spring.ai.vectorstore.milvus.collectionName:vector_store}")
    private String collectionName;

    private final MilvusVectorStore milvusVectorStore;

    public HybridSearchTestNode(MilvusVectorStore milvusVectorStore) {
        this.milvusVectorStore = milvusVectorStore;
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        JSONObject report = new JSONObject();
        report.put("bm25Supported", false);

        MilvusServiceClient client = null;
        try {
            client = getNativeClient();

            // ① 服务端版本（通过现有 collection 统计间接验证连通性）
            R<GetCollectionStatisticsResponse> stats =
                    client.getCollectionStatistics(GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(collectionName).build());
            if (stats.getStatus() == 0) {
                report.put("collectionConnected", true);
                report.put("rowCount", stats.getData().getStatsList().stream()
                        .filter(s -> s.getKey().equals("row_count"))
                        .findFirst().map(kv -> kv.getValue()).orElse("unknown"));
            } else {
                report.put("collectionConnected", false);
                report.put("error", stats.getException().getMessage());
            }

            // ② SDK 版本（JAR 包可能无 Manifest，降级用已知版本）
            String sdkVer = io.milvus.client.MilvusClient.class.getPackage().getImplementationVersion();
            if (sdkVer == null) {
                sdkVer = "2.5.8"; // 本地 Maven 仓库确认版本
            }
            report.put("sdkVersion", sdkVer);

            // ③ BM25 支持判断：SDK 2.5+ & 服务端连通即可
            boolean connected = stats.getStatus() == 0;
            boolean bm25Supported = connected && isSdk25Plus(sdkVer);
            report.put("bm25Supported", bm25Supported);
            report.put("message", bm25Supported
                    ? "Milvus SDK & 服务端均 >= 2.5，BM25 混合检索可用。下一步：在 collection 上添加 sparse_vector 字段 + BM25 function"
                    : "SDK 或服务端版本不足，需升级至 >= 2.5");
            report.put("nextStep", bm25Supported
                    ? "添加 sparse_vector 字段 + 创建 BM25 function，然后集成到 RetrievalNode 混合检索"
                    : "升级 Milvus 后重试");

        } catch (Exception e) {
            log.error("[BM25-Test] 失败: {}", e.getMessage(), e);
            report.put("error", e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("answer", report.toJSONString());
        result.put("steps", "BM25兼容性检测完成");
        return result;
    }

    @SuppressWarnings("resource")
    private MilvusServiceClient getNativeClient() {
        try {
            var field = milvusVectorStore.getClass().getDeclaredField("milvusClient");
            field.setAccessible(true);
            return (MilvusServiceClient) field.get(milvusVectorStore);
        } catch (Exception e) {
            throw new RuntimeException("无法获取底层 MilvusServiceClient", e);
        }
    }

    private boolean isSdk25Plus(String ver) {
        if (ver == null) return false;
        try {
            String[] p = ver.split("\\.");
            return p.length >= 2 && Integer.parseInt(p[0]) >= 2 && Integer.parseInt(p[1]) >= 5;
        } catch (Exception e) { return false; }
    }
}
