package com.bistu.hkgrag.llm.model.Controller;

import com.bistu.hkgrag.llm.agent.TestMultiAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ChatController {
    private static Logger log = LoggerFactory.getLogger(ChatController.class);
    private TestMultiAgent agent;

    public ChatController(@Autowired TestMultiAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestParam String message,
                                    @RequestParam Long sessionId) {
        try {
            if (message == null || message.isBlank()) {
                log.info("收到来自会话[{}]请求，消息内容为空", sessionId);
            }
            log.info("会话[{}] 收到消息：{}", sessionId, message);

            String aiResponse = agent.testMultiAgent(message);

            return Map.of(
                    "code", 200,
                    "message", "success",
                    "data", aiResponse
            );
        } catch (Exception e) {
            e.printStackTrace();
            log.error("AI 对话异常：", e);
            return Map.of(
                    "code", 500,
                    "message", "服务器处理失败",
                    "error", e.getMessage()
            );
        }

    }
}
