package com.bistu.hkgrag.Agent;


import com.bistu.hkgrag.llm.agent.TestMultiAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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


    public MultiAgentTest(@Autowired TestMultiAgent testMultiAgent) {
        this.testMultiAgent = testMultiAgent;
    }

    @Test
    public void testMultiAgent() {
        String question="2016年健翔桥校区因为什么原因导致大学英语考试地点变更？";
        String finalAnswer=testMultiAgent.testMultiAgent(question);
        System.out.println(finalAnswer);
        Assertions.assertNotNull(finalAnswer);
    }
}
