package com.bistu.hkgrag.Service;


import com.bistu.hkgrag.llm.agent.QuestionAnalysisAgent;
import com.bistu.hkgrag.llm.agent.MultiAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class QuestionAnalysisAgentTest {
    public QuestionAnalysisAgentTest(@Autowired QuestionAnalysisAgent agent) {
        this.agent = agent;
    }

    private QuestionAnalysisAgent agent;

    @Test
    public void test_userQuestionAnalyse(){
//        String question="哪些人和吴进在同一个组织，并且提交过文档？";
//        String suggestion="";
//        QuestionAnalysisAgent.QuestionAnalysisResult result=agent.userQuestionAnalyse(question,suggestion);
//        System.out.println(result);
//        List<String> topics=result.topics();
//        List<String> subQuestions=result.subQuestions();
//        System.out.println(topics);
//        System.out.println(subQuestions);
//        Assertions.assertNotNull(result);
        String question="祁忠勇有哪些工作任职经历，最终长期在哪所高校任教？";
        String suggestion="";
        List<MultiAgent.Generation> generations = new ArrayList<>();
        QuestionAnalysisAgent.RetrievalPlanResponse planResponse=agent.retrievePlan(question,generations);
        System.out.println(planResponse);
        Assertions.assertNotNull(planResponse);
    }
}
