package com.bistu.hkgrag.service;


import com.bistu.hkgrag.llm.agent.QuestionAnalysisAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class QuestionAnalysisAgentTest {
    public QuestionAnalysisAgentTest(@Autowired QuestionAnalysisAgent agent) {
        this.agent = agent;
    }

    private QuestionAnalysisAgent agent;

    @Test
    public void test_userQuestionAnalyse(){
        String question="哪些人和吴进在同一个组织，并且提交过文档？";
        String suggestion="";
        QuestionAnalysisAgent.QuestionAnalysisResult result=agent.userQuestionAnalyse(question,suggestion);
        System.out.println(result);
        List<String> topics=result.topics();
        List<String> subQuestions=result.subQuestions();
        System.out.println(topics);
        System.out.println(subQuestions);
        Assertions.assertNotNull(result);
    }
}
