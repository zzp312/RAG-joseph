package com.xushu.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class XushuRagAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(XushuRagAiApplication.class, args);
    }

}
