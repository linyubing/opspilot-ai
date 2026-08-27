package com.opspilot.ai.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金每日结算任务只在明确开启时注册。 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "opspilot.forecast.gold.settlement-schedule.enabled=true",
        "opspilot.forecast.gold.settlement-schedule.cron=0 0 0 1 1 *"
})
class GoldSettlementSchedulingTests {

    @MockitoBean
    private GoldSettlementService settlementService;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("开启配置后注册黄金每日结算任务")
    void registersSchedulerWhenEnabled() {
        assertThat(applicationContext.containsBean("goldSettlementScheduler"))
                .isTrue();
    }
}
