package com.xushu.rag.advisors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MetadataAwareQuestionAnswerAdvisor implements BaseAdvisor {
    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
			{query}

			Context information is below, surrounded by ---------------------

			---------------------
			{question_answer_context}
			---------------------

			Given the context and provided history information and not prior knowledge,
			reply to the user comment. If the answer is not in the context, inform
			the user that you can't answer the question.
			""");
    @Override  
    public ChatClientRequest before(ChatClientRequest baseRequest, AdvisorChain advisorChain) {

        // 获取检索到的文档  
        List<Document> documents = (List<Document>) baseRequest.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        String userMessage = (String) baseRequest.context().get("userMessage");



        if(!CollectionUtils.isEmpty(documents)) {
            String documentContext = documents == null ? ""
                    : documents.stream().map(doc -> doc.getText()+"\n来源文件:"+doc.getMetadata().getOrDefault("source", "unknown").toString()).collect(Collectors.joining(System.lineSeparator()));

            // 重新构建prompt，在末尾添加source信息

            String augmentedUserText = DEFAULT_PROMPT_TEMPLATE
                    .render(Map.of("query", userMessage, "question_answer_context", documentContext));


            return baseRequest.mutate()
                    .prompt(baseRequest.prompt().augmentUserMessage(augmentedUserText))
                    .build();
        }
        else {
            return baseRequest;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        // 优先级最低、因为要保证负责RAG的questionAnswerAdvisor执行完.才能拿到文档信息
        // 为什么不直接设置MAX_VALUE， 因为ChatModelCallAdvisor（负责实际调用AI模型的advisor）也使用了Integer.MAX_VALUE，  如果在他之后，AI对话完成不会执行
        return Integer.MAX_VALUE-1;
    }
}