package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.SaveGoldForecastResult;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证黄金每日研究报告调度的审计日志和异常隔离。 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GoldDailyResearchReportSchedulerTests {

    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    @Mock
    private GoldDailyResearchReportService dailyReportService;

    @Test
    @DisplayName("成功时记录快照、方向和幂等状态")
    void logsAuditMetadataOnSuccess(CapturedOutput output) {
        GoldDailyResearchReportResult result = reportResult();
        when(dailyReportService.generateDailyReport())
                .thenReturn(result);
        GoldDailyResearchReportScheduler scheduler =
                new GoldDailyResearchReportScheduler(dailyReportService);

        scheduler.generateDailyReport();

        assertThat(output.getOut())
                .contains(
                        "黄金每日研究报告生成完成",
                        "快照编号=" + SNAPSHOT_ID,
                        "预测方向=BULLISH",
                        "解读新建=false",
                        "预测新建=true"
                )
                .doesNotContain("模型原始响应", "完整提示词");
    }

    @Test
    @DisplayName("单次失败只记录异常而不抛出到调度线程")
    void isolatesSingleRunFailure(CapturedOutput output) {
        when(dailyReportService.generateDailyReport())
                .thenThrow(new IllegalStateException("数据源暂时不可用"));
        GoldDailyResearchReportScheduler scheduler =
                new GoldDailyResearchReportScheduler(dailyReportService);

        assertThatCode(scheduler::generateDailyReport)
                .doesNotThrowAnyException();
        assertThat(output.getOut())
                .contains(
                        "黄金每日研究报告生成失败",
                        "数据源暂时不可用"
                );
    }

    private GoldDailyResearchReportResult reportResult() {
        StoredGoldResearchSnapshot snapshot =
                mock(StoredGoldResearchSnapshot.class);
        when(snapshot.id()).thenReturn(SNAPSHOT_ID);
        SaveGoldResearchSnapshotResult savedSnapshot =
                new SaveGoldResearchSnapshotResult(snapshot, false);
        GoldResearchPreparationResult preparation =
                mock(GoldResearchPreparationResult.class);
        when(preparation.snapshot()).thenReturn(savedSnapshot);

        StoredGoldDirectionForecast forecastRecord =
                mock(StoredGoldDirectionForecast.class);
        when(forecastRecord.predictedDirection())
                .thenReturn(ForecastDirection.BULLISH);

        return new GoldDailyResearchReportResult(
                preparation,
                new SaveResearchNarrativeResult(
                        mock(StoredResearchNarrative.class),
                        false
                ),
                new SaveGoldForecastResult(forecastRecord, true)
        );
    }
}
