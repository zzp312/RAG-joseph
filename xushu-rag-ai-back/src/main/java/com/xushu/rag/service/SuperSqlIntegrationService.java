package com.xushu.rag.service;

import com.aispace.supersql.builder.TrainBuilder;
import com.aispace.supersql.engine.SpringSqlEngine;
import com.aispace.supersql.enumd.TrainPolicyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * SuperSQL 集成服务
 * 提供 text-to-sql 和训练功能
 */
@Slf4j
@Service
public class SuperSqlIntegrationService {

    @Autowired
    private SpringSqlEngine sqlEngine;

    /**
     * 使用 SuperSQL 的 text-to-sql 功能生成 SQL
     *
     * @param question 自然语言问题
     * @return 生成的 SQL 语句
     */
    public String generateSql(String question) {
        try {
            String sql = sqlEngine.generateSql(question);
            log.info("使用 SuperSQL 生成 SQL: question={}, sql={}", question, sql);
            return sql;
        } catch (Exception e) {
            log.error("使用 SuperSQL 生成 SQL 失败: question={}", question, e);
            return null;
        }
    }

    /**
     * 使用 SuperSQL 训练 DDL
     *
     * @param ddl DDL 语句
     */
    public void trainWithDDL(String ddl) {
        try {
            TrainBuilder trainBuilder = TrainBuilder.builder()
                    .content(ddl)
                    .policy(TrainPolicyType.DDL)
                    .build();
            sqlEngine.train(trainBuilder);
            log.info("成功使用 SuperSQL 训练 DDL: {}", ddl);
        } catch (Exception e) {
            log.error("使用 SuperSQL 训练 DDL 失败: ", e);
        }
    }

    /**
     * 判断是否应该创建表
     * 通过 SuperSQL 的 text-to-sql 功能来判断
     *
     * @param question 自然语言问题
     * @return 是否应该创建表
     */
    public boolean shouldCreateTable(String question) {
        try {
            String sql = generateSql(question);
            // 如果生成的 SQL 包含 CREATE TABLE，则认为应该创建表
            return sql != null && sql.toUpperCase().contains("CREATE TABLE");
        } catch (Exception e) {
            log.error("使用 SuperSQL 判断是否创建表失败: question={}", question, e);
            return false;
        }
    }
}