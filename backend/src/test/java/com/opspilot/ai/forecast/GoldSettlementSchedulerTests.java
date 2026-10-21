package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBarSyncResult;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证定时任务的结算触发、成功日志和异常隔离。 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GoldSettlementSchedulerTests {

    @Mock
    private GoldSettlementService settlementService;

    @Test @DisplayName("结算调度失败日志不输出来源URL、凭据或嵌套异常正文")
    void hidesSourceSecrets(CapturedOutput output) {
        var secret = "test-only-secret-must-not-appear";
        when(settlementService.settleDaily()).thenThrow(new MarketDataUnavailableException(
                "请求地址含api_key=" + secret,
                new IllegalStateException("https://example.invalid?api_key=" + secret)));
        assertThatCode(() -> new GoldSettlementScheduler(settlementService).runSettlement())
                .doesNotThrowAnyException();
        assertThat(output.getAll()).contains("黄金定时结算失败")
                .doesNotContain(secret, "api_key=", "https://example.invalid");
    }

    @Test
    @DisplayName("定时结算成功时记录行情和预测处理数量")
    void logsSettlementCounts(CapturedOutput output) {
        when(settlementService.settleDaily()).thenReturn(new GoldSettlementResult(
                new GoldDailyBarSyncResult(
                        3, 2, 1, LocalDate.parse("2026-08-27")
                ),
                new ResolveGoldForecastsResult(2, 1, 1)
        ));
        GoldSettlementScheduler scheduler =
                new GoldSettlementScheduler(settlementService);

        assertThatCode(scheduler::runSettlement).doesNotThrowAnyException();

        verify(settlementService).settleDaily();
        assertThat(output).contains(
                "黄金定时结算完成",
                "保存行情数=2",
                "成功结算数=1",
                "待结算数=1"
        );
    }

    @Test
    @DisplayName("定时结算失败时记录异常并保持调度线程正常")
    void isolatesSettlementFailure(CapturedOutput output) {
        when(settlementService.settleDaily()).thenThrow(
                new MarketDataUnavailableException("黄金行情暂时不可用")
        );
        GoldSettlementScheduler scheduler =
                new GoldSettlementScheduler(settlementService);

        assertThatCode(scheduler::runSettlement).doesNotThrowAnyException();

        assertThat(output).contains(
                "黄金定时结算失败",
                "异常类型=MarketDataUnavailableException",
                "流程未完整完成"
        );
    }
}
