package com.opspilot.ai.forecast;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启 Spring 定时任务基础设施，具体黄金任务仍由独立开关控制。 */
@Configuration
@EnableScheduling
public class GoldSettlementSchedulingConfiguration {
}
