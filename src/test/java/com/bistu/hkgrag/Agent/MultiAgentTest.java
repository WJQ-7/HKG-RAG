package com.bistu.hkgrag.Agent;


import com.bistu.hkgrag.llm.agent.RetrievalTools;
import com.bistu.hkgrag.llm.agent.TestMultiAgent;
import com.bistu.hkgrag.llm.model.agent.RagResult;
import com.bistu.hkgrag.llm.service.BaseLineService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class MultiAgentTest {
//    private final MultiAgent multiAgent;
//
//
//    public MultiAgentTest(@Autowired MultiAgent multiAgent) {
//        this.multiAgent = multiAgent;
//    }
//
//    @Test
//    public void test_MultiAgentWorkflow() {
//        String question = "学校小营校区 5 月 13 日的心理健康教育广场宣传活动具体在什么位置举办？";
//       // String question="各学院若想了解小营校区 5 月 13 日的心理健康教育广场宣传活动的具体场地布置与方位信息，应通过什么方式联系哪位老师，联系电话是多少？";
//        String answer = multiAgent.MultiAgentWorkflow(question).getAnswer();
//        if (answer.isEmpty() || answer.equals("")) {
//            System.out.println("没有找到答案");
//        }
//        System.out.println("用户问题的答案是：" + answer);
//
//    }

    public final TestMultiAgent testMultiAgent;
    public final RetrievalTools retrievalTools;
    public final BaseLineService baseLineService;


    public MultiAgentTest(@Autowired TestMultiAgent testMultiAgent,
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
