package com.opspilot.ai.forecast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按配置的时间触发黄金行情同步与预测结算，不生成新的大模型预测。 */
@Component
@ConditionalOnProperty(
        prefix = "opspilot.forecast.gold.settlement-schedule",
        name = "enabled",
        havingValue = "true"
)
public class GoldSettlementScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(GoldSettlementScheduler.class);

    private final GoldSettlementService settlementService;

    public GoldSettlementScheduler(GoldSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(
            cron = "${opspilot.forecast.gold.settlement-schedule.cron}",
            zone = "${opspilot.forecast.gold.settlement-schedule.zone}"
    )
    public void runSettlement() {
        try {
            GoldSettlementResult result = settlementService.settleDaily();

            log.info(
                    "黄金定时结算完成，保存行情数={}，成功结算数={}，待结算数={}",
                    result.priceSync().savedCount(),
                    result.forecastResolution().resolvedCount(),
                    result.forecastResolution().pendingCount()
            );
        } catch (RuntimeException exception) {
            // 底层异常链可能带含key的URL；只记异常类型，不记Message或Throwable。
            log.error(
                    "黄金定时结算失败，异常类型={}，流程未完整完成；来源错误详情已隐藏",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
