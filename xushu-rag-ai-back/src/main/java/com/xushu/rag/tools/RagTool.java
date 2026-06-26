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
    private ChatModel chatModel;
    @Tool(description = "涉及统计数据、求和、计数、平均值等聚合操作")
    public String getAggregationQuery(@ToolParam(description = "用户的提问") String question) {
        // 是聚合对话
        // 使用 SuperSQL 的 text-to-sql 功能生成实际的 SQL 查询
        String actualSql=sqlEngine
                .setChatModel(chatModel)
                .setOptions(RagOptions.builder().topN(10).rerank(false).limitScore(0.1).build())
                .generateSql(question);
            Object object = sqlEngine.executeSql(actualSql);

        return JSON.toJSONString( object);
    }
}
