package com.sec.rag.config;

import com.sec.rag.service.Sec10KAssistant;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.service.AiServices;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class LangChainConfig {

    @Bean
    public ChatModel chatModel(AppProperties properties) {
        return OllamaChatModel.builder()
                .baseUrl(properties.getOllama().getBaseUrl())
                .modelName(properties.getOllama().getChatModel())
                .temperature(0.1)
                .timeout(Duration.ofSeconds(properties.getOllama().getTimeoutSeconds()))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(AppProperties properties) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(properties.getOllama().getBaseUrl())
                .modelName(properties.getOllama().getEmbeddingModel())
                .timeout(Duration.ofSeconds(properties.getOllama().getTimeoutSeconds()))
                .build();
    }

    @Bean
    public Sec10KAssistant sec10KAssistant(ChatModel chatModel) {
        return AiServices.builder(Sec10KAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public WebClient qdrantWebClient(AppProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.getQdrant().getUrl())
                .build();
    }
}
