package com.bistu.hkgrag.Agent;


import com.bistu.hkgrag.llm.agent.RetrievalTools;
import com.bistu.hkgrag.llm.agent.MultiAgent;
import com.bistu.hkgrag.llm.service.BaseLineService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class MultiAgentTest {
    public final MultiAgent testMultiAgent;
    public final RetrievalTools retrievalTools;
    public final BaseLineService baseLineService;


    public MultiAgentTest(@Autowired MultiAgent testMultiAgent,
                          @Autowired RetrievalTools retrievalTools,
                          @Autowired BaseLineService baseLineService) {
        this.testMultiAgent = testMultiAgent;
        this.retrievalTools = retrievalTools;
        this.baseLineService = baseLineService;
    }

    @Test
    public void testMultiAgent() {
//        如何申请东方睿嬴（北京）软件技术有限公司”实习生职位以及联系方式是什么
        String question = "哪些校友企业招聘的岗位明确面向在校大学生或应届毕业生？ ";
        String finalAnswer=testMultiAgent.testMultiAgent(question);
        System.out.println(finalAnswer);
        Assertions.assertNotNull(finalAnswer);
    }

    @Test
    public void testBaselineRag(){
        String question = "评审委员会在同等学术水平下是否应优先考虑北京信息科技大学经济管理学院教师的丰耘青年教师优秀科研论文奖励基金申请？";
        String finalAnswer=baseLineService.naiveRAG(question).getAnswer();
        System.out.println(finalAnswer);
        Assertions.assertNotNull(finalAnswer);

        finalAnswer = testMultiAgent.testMultiAgent(question);
        System.out.println(finalAnswer);
        Assertions.assertNotNull(finalAnswer);
    }

    @Test
    public void testSearchEntityByName(){
        List<String> names=new ArrayList<String>();
        names.add("高等数学");
        RetrievalTools.RetrievalResult result=retrievalTools.searchEntityByName(names);
        Assertions.assertNotNull(result);

    }
}
