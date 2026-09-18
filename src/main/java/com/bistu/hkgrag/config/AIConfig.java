package com.bistu.hkgrag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class AIConfig {

    // ==================== 模型配置（从 application.properties 注入） ====================

    private final String deepseekBaseUrl;
    private final String deepseekApiKey;
    private final String deepseekModel;
    private final double deepseekTemperature;

    private final String qwenBaseUrl;
    private final String qwenApiKey;
    private final String qwenModel;
    private final double qwenTemperature;
    private final long qwenConnectTimeoutSeconds;
    private final long qwenReadTimeoutMinutes;

    private final String embeddingBaseUrl;
    private final String embeddingApiKey;
    private final String embeddingModel;

    public AIConfig(
            @Value("${HKG-RAG.llm.deepseek.base-url}") String deepseekBaseUrl,
            @Value("${HKG-RAG.llm.deepseek.api-key}") String deepseekApiKey,
            @Value("${HKG-RAG.llm.deepseek.model}") String deepseekModel,
            @Value("${HKG-RAG.llm.deepseek.temperature:0.5}") double deepseekTemperature,
            @Value("${HKG-RAG.llm.qwen.base-url}") String qwenBaseUrl,
            @Value("${HKG-RAG.llm.qwen.api-key}") String qwenApiKey,
            @Value("${HKG-RAG.llm.qwen.model}") String qwenModel,
            @Value("${HKG-RAG.llm.qwen.temperature:0.1}") double qwenTemperature,
            @Value("${HKG-RAG.llm.qwen.connect-timeout-seconds:30}") long qwenConnectTimeoutSeconds,
            @Value("${HKG-RAG.llm.qwen.read-timeout-minutes:10}") long qwenReadTimeoutMinutes,
            @Value("${HKG-RAG.embedding.base-url}") String embeddingBaseUrl,
            @Value("${HKG-RAG.embedding.api-key}") String embeddingApiKey,
            @Value("${HKG-RAG.embedding.model}") String embeddingModel
    ) {
        this.deepseekBaseUrl = deepseekBaseUrl;
        this.deepseekApiKey = deepseekApiKey;
        this.deepseekModel = deepseekModel;
        this.deepseekTemperature = deepseekTemperature;
        this.qwenBaseUrl = qwenBaseUrl;
        this.qwenApiKey = qwenApiKey;
        this.qwenModel = qwenModel;
        this.qwenTemperature = qwenTemperature;
        this.qwenConnectTimeoutSeconds = qwenConnectTimeoutSeconds;
        this.qwenReadTimeoutMinutes = qwenReadTimeoutMinutes;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingApiKey = embeddingApiKey;
        this.embeddingModel = embeddingModel;
    }

    /**
     * deepseekChatClient
     *
     * @return
     */
    @Bean
    @Qualifier("deepseekV4")
    public ChatClient deepseekChatClient() {

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(deepseekApiKey)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(deepseekModel)
                .temperature(deepseekTemperature)
                .build();

        OpenAiChatModel qwenModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();

        return ChatClient.builder(qwenModel).build();
    }

    /**
     * qwen7bInsChatClient
     *
     * @return
     */
    @Bean
    @Qualifier("qwen7bInsChatClient")
    public ChatClient qwen7bInsChatClient() {
        // 连接超时
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(qwenConnectTimeoutSeconds))
                .build();

        // RestClient 请求超时
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(qwenReadTimeoutMinutes));

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(qwenBaseUrl)
                .apiKey(qwenApiKey)
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(httpClient)))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(qwenModel)
                .temperature(qwenTemperature)
                .build();

        OpenAiChatModel qwenModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();

        return ChatClient.builder(qwenModel).build();
    }

    @Bean
    @Qualifier("qwenEmbeddingModel")
    public EmbeddingModel qwenEmbeddingModel() {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        OpenAiApi embeddingApi = OpenAiApi.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(httpClient)))
                .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient)))
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(embeddingModel)
                .build();
        OpenAiEmbeddingModel embeddingModel =
                new OpenAiEmbeddingModel(embeddingApi, MetadataMode.EMBED, options);
        return embeddingModel;
    }





}
