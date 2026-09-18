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


//    /**
//     * qwen3Max-1000b-online
//     *
//     * @return
//     */
//    @Bean
//    @Qualifier("qwen3MaxChatModel")
//    public OpenAiChatModel qwen3MaxChatModel() {
//
//        OpenAiApi api = OpenAiApi.builder()
//                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
//                .apiKey("sk-1435466c2c4049ee9c7eb8bac30c873a")
//                .build();
//
//        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
//                .model("qwen3-max")
//                .temperature(0.2)
//                .build();
//
//
//        return OpenAiChatModel.builder()
//                .openAiApi(api)
//                .defaultOptions(defaultOptions)
//                .build();
//    }
//
//    /**
//     * qwen3Max-1000b-online
//     *
//     * @return
//     */
//    @Bean
//    @Qualifier("qwen7bInsChatModel")
//    public OpenAiChatModel qwen7bInsChatModel() {
//        HttpClient httpClient = HttpClient.newBuilder()
//                .version(HttpClient.Version.HTTP_1_1)
//                .build();
//
//        OpenAiApi api = OpenAiApi.builder()
//                // .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
//                .baseUrl("http://10.146.130.140:8001")
//                .apiKey("sk-1435466c2c4049ee9c7eb8bac30c873a")
//                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(httpClient)))
//                .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient)))
//                .build();
//
//        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
//                // .model("qwen2.5-7b-instruct")
//                .model("/home/wjq/qwen2.5-7b-instruct")
//                .temperature(0.2)
//                .build();
//
//
//        return OpenAiChatModel.builder()
//                .openAiApi(api)
//                .defaultOptions(defaultOptions)
//                .build();
//    }
//

    /**
     * qwen3Max-1000b-online
     *
     * @return
     */
//    @Bean
//    @Qualifier("qwen3MaxChatClient")
//    public ChatClient qwen3MaxChatClient() {
//        OpenAiApi api = OpenAiApi.builder()
//                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
//                .apiKey("sk-1435466c2c4049ee9c7eb8bac30c873a")
//                .build();
//
//        OpenAiChatOptions options = OpenAiChatOptions.builder()
//                .model("qwen3-max")
//                .temperature(0.7)
//                .build();
//
//        OpenAiChatModel qwenModel = OpenAiChatModel.builder()
//                .openAiApi(api)
//                .defaultOptions(options)
//                .build();
//
//        return ChatClient.builder(qwenModel).build();
//    }

    /**
     * qwen7bInsChatClient
     *
     * @return
     */
    @Bean
    @Qualifier("deepseekV4")
    public ChatClient deepseekChatClient() {


        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey("sk-990a74e4f73444b194caf3ffb4c0cce3")
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .temperature(0.5)
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
        // 连接超时30秒，请求超时4分钟（超时后抛异常，由上层重试队列接管）
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // RestClient 请求超时4分钟
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(10));

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .apiKey("sk-1435466c2c4049ee9c7eb8bac30c873a")
//                  .baseUrl("http://10.251.1.122:31006")
                .baseUrl("http://10.146.130.107:8001")
//               .baseUrl("http://127.0.0.1:8001")
//                .apiKey("sk-1435466c2c4049ee9c7eb8bac30c873a")
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(httpClient)))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
//                .model("qwen3-max")
//                .model("/workspace/chen/wjq/model/qwen2.5-14b-instruct")
              .model("/home/wjq/qwen2.5-14b-instruct")
//             .model("/home/featurize/work/qwen2.5-14b-instruct")
                .temperature(0.1)
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
//                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
//                .apiKey("sk-1435466c2c4049ee9c7eb8bac30c873a")
//                .baseUrl("http://10.146.130.140:8003")
//                .baseUrl("http://127.0.0.1:8001")
                .baseUrl("http://10.251.1.122:31002")
                .apiKey("sk-1435466c2c4049ee9c7eb8bac30c873a")
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(httpClient)))
                .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient)))
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
//                .model("text-embedding-v4")
//                .model("/home/featurize/work/qwen3-4b-embedding")
//                .model("/home/wjq/qwen3-4b-embedding")
                .model("/workspace/chen/wjq/model/qwen3-embedding-4b")
                .build();
        OpenAiEmbeddingModel embeddingModel =
                new OpenAiEmbeddingModel(embeddingApi, MetadataMode.EMBED, options);
        return embeddingModel;
    }





}
