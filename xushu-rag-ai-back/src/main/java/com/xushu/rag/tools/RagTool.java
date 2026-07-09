package com.xushu.rag.tools;

import com.aispace.supersql.builder.RagOptions;
import com.aispace.supersql.engine.SpringSqlEngine;
import com.alibaba.fastjson.JSON;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RagTool {


    @Autowired
    private SpringSqlEngine sqlEngine;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private ChatModel chatModel;
    @Tool(description = "涉及数据表查询，包括统计数据、求和、计数、平均值等聚合操作，或者用户直接提出查数据库的时候")
    public String getAggregationQuery(@ToolParam(description = "用户的提问") String question) {
        String actualSql = sqlEngine
                .setChatModel(chatModel)
                .setOptions(RagOptions.builder().topN(10).rerank(false).limitScore(0.1).build())
                .generateSql(question);
        if (actualSql == null) {
            return "{\"error\": \"generateSql returned null\"}";
        }
        Object object = sqlEngine.executeSql(actualSql.trim());
        return JSON.toJSONString(object);
    }
}
