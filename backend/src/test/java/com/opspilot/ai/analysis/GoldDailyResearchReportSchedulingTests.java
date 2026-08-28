package com.opspilot.ai.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金每日研究报告任务只在明确开启时注册。 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "opspilot.research.gold.daily-report-schedule.enabled=true",
        "opspilot.research.gold.daily-report-schedule.cron=0 0 0 1 1 *"
})
class GoldDailyResearchReportSchedulingTests {

    @MockitoBean
    private GoldDailyResearchReportService dailyReportService;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("开启配置后注册黄金每日研究报告任务")
    void registersSchedulerWhenEnabled() {
        assertThat(applicationContext.containsBean(
                "goldDailyResearchReportScheduler"
        )).isTrue();
    }
}
