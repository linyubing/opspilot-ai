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

/** 验证候选提示词保留历史事实，并加入待验证的改进规则。 */
class CandidateBacktestPromptBuilderTests {

    @Test
    void buildsCandidatePrompt() {
        var builder = new CandidateBacktestPromptBuilder();

        var prompt = builder.build(UUID.randomUUID(), snapshot());

        assertThat(prompt.version()).isEqualTo(
                CandidateBacktestPromptBuilder.VERSION
        );
        assertThat(prompt.content())
                .contains("分析日期：2026-08-20")
                .contains("候选规则")
                .contains("因子发生冲突时")
                .contains("不得使用未来数据");
        assertThat(prompt.sha256()).hasSize(64);
    }

    private GoldResearchSnapshot snapshot() {
        var now = OffsetDateTime.parse("2026-08-20T08:00:00Z");
        var date = LocalDate.parse("2026-08-20");
        return new GoldResearchSnapshot(
                date, date, date, date,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), new BigDecimal("0.2"),
                        new BigDecimal("0.8"), new BigDecimal("1.5"), now
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("1.8"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("5"), new BigDecimal("12"), now
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        new BigDecimal("0.6"), new BigDecimal("1.1"), now
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.PRESSURING, "rate-v1", "实际利率构成压力"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.PRESSURING, "dollar-v1", "美元走强构成压力"
                ),
                "gold-multifactor-v2", "不构成投资建议"
        );
    }
}
