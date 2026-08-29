package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证第三版提示词明确因子优先级，并限制无依据的中性预测。 */
class ImprovedBacktestPromptBuilderTests {

    @Test
    void buildsImprovedPrompt() {
        var prompt = new ImprovedBacktestPromptBuilder()
                .build(UUID.randomUUID(), snapshot());

        assertThat(prompt.version()).isEqualTo(
                ImprovedBacktestPromptBuilder.VERSION
        );
        assertThat(prompt.content())
                .contains("长期方向")
                .contains("打破平局")
                .contains("中性只能用于")
                .contains("不得使用未来数据");
        assertThat(prompt.sha256()).hasSize(64);
    }

    private GoldResearchSnapshot snapshot() {
        var now = OffsetDateTime.parse("2026-08-20T08:00:00Z");
        var date = LocalDate.parse("2026-08-20");
        return new GoldResearchSnapshot(
                date, date, date, date,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), new BigDecimal("-0.1"),
                        new BigDecimal("0.4"), new BigDecimal("1.2"), now
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("1.8"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("3"), new BigDecimal("8"), now
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        new BigDecimal("-0.2"), new BigDecimal("-0.6"), now
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.PRESSURING, "rate-v1", "实际利率构成压力"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE, "dollar-v1", "美元回落形成支撑"
                ),
                "gold-multifactor-v2", "不构成投资建议"
        );
    }
}
