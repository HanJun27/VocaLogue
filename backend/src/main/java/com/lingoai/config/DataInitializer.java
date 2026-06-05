package com.lingoai.config;

import com.lingoai.entity.Scenario;
import com.lingoai.repository.ScenarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 数据初始化器 - 初始化场景数据
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final ScenarioRepository scenarioRepository;

    @Override
    public void run(String... args) {
        if (scenarioRepository.count() == 0) {
            log.info("初始化场景数据...");
            
            Scenario interview = Scenario.builder()
                    .id("interview")
                    .title("面试准备")
                    .tag("技术面试")
                    .emoji("💼")
                    .difficulty(3)
                    .description("模拟外企技术岗面试场景，提升面试英语口语能力")
                    .welcomeMessage("Hello, I'm the interviewer. Please introduce yourself.")
                    .welcomeTranslation("你好，我是面试官。请做自我介绍。")
                    .build();

            Scenario restaurant = Scenario.builder()
                    .id("restaurant")
                    .title("餐厅点餐")
                    .tag("日常对话")
                    .emoji("🍽️")
                    .difficulty(1)
                    .description("模拟在西餐厅点餐的日常对话场景")
                    .welcomeMessage("Good evening, welcome to our restaurant. May I take your order?")
                    .welcomeTranslation("晚上好，欢迎光临我们餐厅。请问要点些什么？")
                    .build();

            Scenario meeting = Scenario.builder()
                    .id("meeting")
                    .title("国际会议")
                    .tag("商务英语")
                    .emoji("🌍")
                    .difficulty(2)
                    .description("模拟国际商务会议场景，提升专业英语表达能力")
                    .welcomeMessage("Good morning everyone, let's start the meeting.")
                    .welcomeTranslation("大家早上好，让我们开始会议。")
                    .build();

            scenarioRepository.saveAll(Arrays.asList(interview, restaurant, meeting));
            
            log.info("场景数据初始化完成");
        }
    }

}
