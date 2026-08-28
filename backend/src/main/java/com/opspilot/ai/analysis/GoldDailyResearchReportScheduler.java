package com.opspilot.ai.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按配置时间生成黄金每日研究报告，并隔离单次外部调用故障。 */
@Component
@ConditionalOnProperty(
        prefix = "opspilot.research.gold.daily-report-schedule",
        name = "enabled",
        havingValue = "true"
)
public class GoldDailyResearchReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            GoldDailyResearchReportScheduler.class
    );

    private final GoldDailyResearchReportService dailyReportService;

    public GoldDailyResearchReportScheduler(
            GoldDailyResearchReportService dailyReportService
    ) {
        this.dailyReportService = dailyReportService;
    }

    @Scheduled(
            cron = "${opspilot.research.gold.daily-report-schedule.cron}",
            zone = "${opspilot.research.gold.daily-report-schedule.zone}"
    )
    public void generateDailyReport() {
        try {
            GoldDailyResearchReportResult result =
                    dailyReportService.generateDailyReport();

            log.info(
                    "黄金每日研究报告生成完成，快照编号={}，预测方向={}，解读新建={}，预测新建={}",
                    result.preparation().snapshot().record().id(),
                    result.forecast().record().predictedDirection(),
                    result.narrative().created(),
                    result.forecast().created()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "黄金每日研究报告生成失败，原因={}",
                    exception.getMessage(),
                    exception
            );
        }
    }
}
