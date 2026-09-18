package com.bistu.hkgrag.llm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AdvisorConfig {
    private final Logger log = LoggerFactory.getLogger(AdvisorConfig.class);
    @Bean
    public ToolCallAdvisor toolCallAdvisor(
            ToolCallingManager toolCallingManager
    ) {

        return ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .build();
    }

    @Bean
    public ChatMemory chatMemory() {

        ChatMemory delegate = MessageWindowChatMemory.builder().build();

        return new ChatMemory() {

            @Override
            public void add(String conversationId, List<Message> messages) {
//                log.info("[MEMORY WRITE] id={}, messages={}", conversationId, messages);
                delegate.add(conversationId, messages);
            }

            @Override
            public List<Message> get(String conversationId) {
                return delegate.get(conversationId);
            }

            @Override
            public void clear(String conversationId) {
                delegate.clear(conversationId);
            }
        };
    }

    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(
            ChatMemory chatMemory) {

        return MessageChatMemoryAdvisor.builder(chatMemory)
                .build();
    }


}
