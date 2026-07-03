package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 上下文构建Node
 * <p>将检索到的父页面文档拼接为RAG上下文，处理图片Markdown渲染</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class ContextBuildNode {

    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(Map<String, Object> state) {
        List<Document> documents = (List<Document>) state.getOrDefault(StateKeys.DOCUMENTS,
                Collections.emptyList());

        if (documents.isEmpty()) {
            return Map.of(
                    StateKeys.CONTEXT, "知识库中暂无相关内容",
                    StateKeys.STEPS, "上下文构建: 无检索结果"
            );
        }

        StringBuilder context = new StringBuilder();
        Set<String> addedSources = new HashSet<>();

        // 检测上下文中是否包含图片，提前注入图片处理指引
        boolean hasImages = documents.stream()
                .anyMatch(d -> "IMAGE".equalsIgnoreCase(
                        Objects.toString(d.getMetadata().get("chunk_type"), "")));
        if (hasImages) {
            context.append("【强制要求】以下上下文包含图片，每张图片标注了【必须展示此图片】，")
                    .append("你必须将每张图片的Markdown语法原样输出到回答中，不得遗漏任何一张。\n\n");
        }

        for (Document doc : documents) {
            String source = Objects.toString(doc.getMetadata().get("source"), "未知");
            String version = Objects.toString(doc.getMetadata().get("version"), "");
            String kbName = Objects.toString(doc.getMetadata().get("kb_name"), "");
            Object pageObj = doc.getMetadata().get("page");
            String chunkType = Objects.toString(doc.getMetadata().get("chunk_type"), "");

            boolean isOldVersion = addedSources.contains(source);

            if (context.length() > 0) {
                context.append("\n\n");
            }

            if (isOldVersion) {
                context.append("[旧版本文档] ");
            }

            context.append("来源：").append(kbName).append("/").append(source);
            if (!version.isEmpty()) {
                context.append(" 版本：").append(version);
            }
            if (!chunkType.isEmpty() && !"PARENT_PAGE".equalsIgnoreCase(chunkType)) {
                context.append(" [").append(chunkType).append("]");
            }

            // 图片类型：把描述文本和可展示URL一起交给LLM，并明确要求输出Markdown图片语法
            if ("IMAGE".equalsIgnoreCase(chunkType)) {
                Object imgUrl = doc.getMetadata().get("image_url");
                String imgText = doc.getText().replace("\\n", "\n").replace("\\t", "\t");
                if (imgUrl != null && !imgUrl.toString().isEmpty()) {
                    String safeUrl = imgUrl.toString().replace(" ", "%20");
                    String altText = imgText.length() > 30
                            ? imgText.substring(0, 30).replace("\n", " ") + "..."
                            : imgText.replace("\n", " ");
                    context.append("\n图片内容描述：").append(imgText);
                    context.append("\n【必须展示此图片】将以下Markdown原样输出到回答中：![")
                            .append(altText).append("](").append(safeUrl).append(")");
                } else {
                    context.append("\n").append(imgText);
                }
            } else {
                // 修复：document_pages回表文本中的 \\n 字面量替换为真正换行
                String formattedText = doc.getText()
                        .replace("\\n", "\n")
                        .replace("\\t", "\t");
                context.append("\n").append(formattedText);
            }
            addedSources.add(source);
        }

        // 收集来源摘要供LLM引用
        Map<String, String> sourceSummary = new LinkedHashMap<>();
        for (Document doc : documents) {
            String source = Objects.toString(doc.getMetadata().get("source"), "未知");
            String version = Objects.toString(doc.getMetadata().get("version"), "");
            String key = source + "|" + version;
            sourceSummary.putIfAbsent(key, source + " (版本" + version + ")");
        }
        if (!sourceSummary.isEmpty()) {
            context.append("\n\n---\n📚 可引用的文档来源：\n");
            for (String summary : sourceSummary.values()) {
                context.append("- ").append(summary).append("\n");
            }
        }

        String contextText = context.toString();
        long imageDocCount = documents.stream()
                .filter(d -> "IMAGE".equalsIgnoreCase(
                        Objects.toString(d.getMetadata().get("chunk_type"), "")))
                .count();
        long imageUrlCount = documents.stream()
                .filter(d -> "IMAGE".equalsIgnoreCase(
                        Objects.toString(d.getMetadata().get("chunk_type"), ""))
                        && d.getMetadata().get("image_url") != null)
                .count();
        log.info("[ContextBuild] 构建上下文: {}父页面, {}字符, IMAGE类型{}个(含URL{}个)",
                documents.size(), contextText.length(), imageDocCount, imageUrlCount);

        return Map.of(
                StateKeys.CONTEXT, contextText,
                StateKeys.STEPS, "上下文构建: " + documents.size() + "个来源, "
                        + contextText.length() + "字符"
        );
    }
}
