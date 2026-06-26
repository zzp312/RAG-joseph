package com.xushu.rag.controller;

import com.xushu.rag.annotation.Loggable;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.context.BaseContext;
import com.xushu.rag.entity.SensitiveWord;
import com.xushu.rag.exception.BusinessException;
import com.xushu.rag.service.SensitiveWordService;
import com.xushu.rag.utils.SearchUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;


/**
 * @Title: ChatController
 * @Author Xushu
 * @Package com.Xushu.rag.controller
 * @description: 对话接口
 */

@Tag(name="AiRagController",description = "chat对话接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/chat")
public class ChatController {

    @Autowired
    private  ChatClient chatClient;

    @Autowired
    private SensitiveWordService sensitiveWordService;


    public ChatController(ChatClient.Builder builder,ChatMemory chatMemory) {

        this.chatClient = builder
                .defaultSystem("""
                        你是一家名为“XS公司”的知识库系统的客户客服代理。请友好乐于助人，充满喜悦地回复。
                        """)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build() // CHAT MEMORY

                        )
                .build();
    }

    @Operation(summary = "stream",description = "流式对话接口")
    @GetMapping(value = "/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Loggable("message")
    public Flux<String> streamRagChat(@RequestParam(value = "message", defaultValue = "你好" ) String message,
                                      @RequestParam(value = "prompt", defaultValue = "你是一名AI助手，致力于帮助人们解决问题.") String prompt){
        List<SensitiveWord> list = sensitiveWordService.list();

        for(SensitiveWord sensitiveWord: list){
            if (message.contains(sensitiveWord.getWord())){
                return Flux.just("包含敏感词:" + sensitiveWord.getWord());
            }
        }

        Long userId = BaseContext.getCurrentId();
        return chatClient.prompt()
                .system(prompt)
                .advisors(a -> a
                        .param(ChatMemory.CONVERSATION_ID, userId))
                .user(message)
                .stream()
                .content();
    }






}
