package com.bistu.hkgrag.llm.evaluation;

import com.bistu.hkgrag.config.PromptConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AssessService {
    private final Logger log = LoggerFactory.getLogger(AssessService.class);
    private final ChatClient chatClient;
    private final ChatClient deepseekClient;
    private final PromptConfig promptConfig;

    public AssessService(@Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
                         @Qualifier("deepseekV4") @Autowired ChatClient deepseekClient,
                         @Autowired PromptConfig promptConfig) {
        this.chatClient = chatClient;
        this.deepseekClient = deepseekClient;
        this.promptConfig = promptConfig;
    }

    @Schema(description = "答案评估结果")
    public record AssessResult(
            @Schema(description = "内容全面性评估")
            EvaluationItem contentComprehensiveness,

            @Schema(description = "答案正确性评估")
            EvaluationItem empowerment,

            @Schema(description = "多样性评估")
            EvaluationItem diversity,

            @Schema(description = "整体质量评估")
            EvaluationItem overall
    ) {
        // 嵌套的评分项：包含 score + explanation
        @Schema(description = "评估明细项")
        public record EvaluationItem(
                @Schema(description = "评分 1-10分")
                Integer score,

                @Schema(description = "评分说明/解释")
                String explanation
        ) {}
    }
    private static final int MAX_RETRIES = 3;

    public AssessResult Assess(String query,String pred, String gold) {
        String user_prompt = promptConfig.assessUserPrompt().formatted(query,pred, gold);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                AssessResult result = deepseekClient.prompt()
                        .user(user_prompt)
                        .call()
                        .entity(AssessResult.class);
                if (isValid(result)) {
                    return result;
                }
                log.warn("评估结果不完整（第{}次），重试中...", attempt);
            } catch (Exception e) {
                log.warn("评估解析失败（第{}次）: {}", attempt, e.getMessage());
            }
        }
        log.error("评估重试{}次均失败", MAX_RETRIES);
        return null;
    }

    private boolean isValid(AssessResult result) {
        if (result == null) return false;
        return result.contentComprehensiveness() != null && result.contentComprehensiveness().score() != null
                && result.empowerment() != null && result.empowerment().score() != null
                && result.diversity() != null && result.diversity().score() != null
                && result.overall() != null && result.overall().score() != null;
    }
}
