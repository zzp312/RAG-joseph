package com.xushu.rag.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.xushu.rag.mapper")
public class MyBatisPlusConfig {
}