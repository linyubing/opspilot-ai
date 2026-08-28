package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.forecast.GoldForecastPrompt;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证历史回测提示词只包含预测时点已经知道的研究事实。 */
class BacktestPromptBuilderTests {

    @Test
    void buildsBlindHistoricalPrompt() {
        UUID caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        GoldForecastPrompt prompt = new BacktestPromptBuilder()
                .build(caseId, snapshot());

        assertThat(prompt.version()).isEqualTo("gold-backtest-prompt-v1");
        assertThat(prompt.sha256()).hasSize(64);
        assertThat(prompt.content())
                .contains("历史日期的盲测输入")
                .contains(caseId.toString())
                .contains("2026-08-20")
                .contains("2500")
                .contains("BULLISH|NEUTRAL|BEARISH")
                .contains("只返回 JSON")
                .doesNotContain("2520");
    }

    private GoldResearchSnapshot snapshot() {
        LocalDate date = LocalDate.parse("2026-08-20");
        OffsetDateTime time = OffsetDateTime.parse("2026-08-20T08:00:00Z");
        return new GoldResearchSnapshot(
                date,
                date,
                date,
                date,
                new GoldReturnMetrics(
                        new BigDecimal("2500"),
                        new BigDecimal("0.5"),
                        new BigDecimal("1.2"),
                        new BigDecimal("2.1"),
                        time
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("1.8"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        time
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        time
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE,
                        "real-rate-v1",
                        "实际利率回落"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL,
                        "dollar-v1",
                        "美元指数变化有限"
                ),
                "gold-multifactor-v2",
                "不构成投资建议"
        );
    }
}
