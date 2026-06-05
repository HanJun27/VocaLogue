package com.lingoai.config;

import com.lingoai.entity.Scenario;
import com.lingoai.entity.ScenarioQuestion;
import com.lingoai.repository.ScenarioQuestionRepository;
import com.lingoai.repository.ScenarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 数据初始化器 - 初始化场景数据
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final ScenarioRepository scenarioRepository;
    private final ScenarioQuestionRepository questionRepository;

    @Override
    public void run(String... args) {
        if (scenarioRepository.count() == 0) {
            log.info("初始化场景数据...");
            
            Scenario interview = Scenario.builder()
                    .id("frontend")
                    .title("面试准备")
                    .tag("Web Dev")
                    .emoji("💼")
                    .difficulty(3)
                    .description("技术/行为面试练习。模拟真实 HR 或技术主管的提问风格，锻炼抗压表达。")
                    .welcomeMessage("Hello! Welcome to the interview. To start off, could you please tell me a little bit about yourself and your background in software development?")
                    .welcomeTranslation("你好！欢迎参加面试。首先，能否简单介绍一下你自己以及你在软件开发方面的背景？")
                    .build();

            Scenario restaurant = Scenario.builder()
                    .id("restaurant")
                    .title("餐厅点餐")
                    .tag("Food & Drink")
                    .emoji("🍔")
                    .difficulty(1)
                    .description("生活口语基础。从预定位置、询问菜单到结账，掌握日常出行的必备句型。")
                    .welcomeMessage("Hello! Welcome to Lingo Bistro. I will be your server today. Would you like to start with some drinks or check our specials?")
                    .welcomeTranslation("您好！欢迎光临 Lingo 小酒馆。我是您今天的服务员。您想先喝点饮品，还是看看我们的今日特供？")
                    .build();

            Scenario meeting = Scenario.builder()
                    .id("conference")
                    .title("国际会议")
                    .tag("Business")
                    .emoji("📢")
                    .difficulty(2)
                    .description("商务听力与表达。练习在多人会议中陈述观点、提问及应对复杂讨论的技巧。")
                    .welcomeMessage("Welcome everyone to our Q2 international business review. Let's discuss our globalization metrics. How did our new regional campaign perform last quarter?")
                    .welcomeTranslation("欢迎各位来到我们第二季度的国际业务回顾会议。我们来商讨一下全球化指标。上个季度我们新的区域推广活动表现如何？")
                    .build();

            scenarioRepository.saveAll(Arrays.asList(interview, restaurant, meeting));
            log.info("场景数据初始化完成");

            // 初始化问题数据
            if (questionRepository.count() == 0) {
                log.info("初始化场景问题数据...");
                
                List<ScenarioQuestion> interviewQuestions = Arrays.asList(
                    ScenarioQuestion.builder()
                        .id("int_q1")
                        .scenarioId("frontend")
                        .questionText("That is wonderful. Since you mentioned React, can you explain the critical differences between Server Components and Client Components in React 19, and when to use each?")
                        .questionTranslation("太赞了。既然你提到了 React，你能解释一下 React 19 中服务端组件和客户端组件的关键区别，以及何时使用它们吗？")
                        .orderIndex(1)
                        .keywords("[]")
                        .build(),
                    ScenarioQuestion.builder()
                        .id("int_q2")
                        .scenarioId("frontend")
                        .questionText("Excellent clarity. Now, regarding CSS and design systems, how do you manage utility-first styles in Tailwind CSS to maintain highly modular and customizable responsive UI layers?")
                        .questionTranslation("极其清晰的表述。那么，有关 CSS 和设计系统，你如何管理 Tailwind CSS 中的实用优先样式，以维护高度模块化的响应式 UI 界面？")
                        .orderIndex(2)
                        .keywords("[]")
                        .build(),
                    ScenarioQuestion.builder()
                        .id("int_q3")
                        .scenarioId("frontend")
                        .questionText("Perfect. Finally, in high-traffic applications, how do you diagnostic and optimize slow re-renders using standard React dev tools or profiling hooks?")
                        .questionTranslation("完美。最后一个问题，在高流量应用中，你如何通过开发者工具诊断并优化缓慢的重绘现象？")
                        .orderIndex(3)
                        .keywords("[]")
                        .build()
                );

                List<ScenarioQuestion> restaurantQuestions = Arrays.asList(
                    ScenarioQuestion.builder()
                        .id("res_q1")
                        .scenarioId("restaurant")
                        .questionText("Excellent choice. Our chef recommends the pan-seared ribeye steak or the creamy garlic seafood pasta today. What would you like to have for your main course?")
                        .questionTranslation("优秀的选项。我们的大厨今天推荐香煎肋眼牛排或奶油蒜蓉海鲜面。您的主菜想吃点什么呢？")
                        .orderIndex(1)
                        .keywords("[]")
                        .build(),
                    ScenarioQuestion.builder()
                        .id("res_q2")
                        .scenarioId("restaurant")
                        .questionText("Perfect! And how would you like your steak done? We also offer various delicious sides like truffle fries or roasted asparagus.")
                        .questionTranslation("完美！您的牛排需要几分熟？我们还提供黑松露薯条或烤芦笋等多种可口配菜。")
                        .orderIndex(2)
                        .keywords("[]")
                        .build()
                );

                List<ScenarioQuestion> meetingQuestions = Arrays.asList(
                    ScenarioQuestion.builder()
                        .id("con_q1")
                        .scenarioId("conference")
                        .questionText("That sounds promising. However, our user engagement rate in some markets saw a slight decline. What adjustments should we introduce in the upcoming sprint to optimize this?")
                        .questionTranslation("这听起来很有前景。然而，我们在某些市场的用户参与率出现了轻微下滑。在接下来的迭代中，我们应该引入哪些调整来优化这一点？")
                        .orderIndex(1)
                        .keywords("[]")
                        .build(),
                    ScenarioQuestion.builder()
                        .id("con_q2")
                        .scenarioId("conference")
                        .questionText("Great suggestions. Let's compile these into our roadmap. Any other concerns or updates regarding our server scaling capability before we wrap up?")
                        .questionTranslation("极好的建议。我们将把这些整理进我们的路线图。在结束会议前，关于我们服务器扩容能力，大家还有其他疑问或更新汇报吗？")
                        .orderIndex(2)
                        .keywords("[]")
                        .build()
                );

                questionRepository.saveAll(interviewQuestions);
                questionRepository.saveAll(restaurantQuestions);
                questionRepository.saveAll(meetingQuestions);
                log.info("场景问题数据初始化完成");
            }
        }
    }

}
