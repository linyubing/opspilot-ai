package com.opspilot.ai.analysis.narrative;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchNarrativePromptBuilderTests {

    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.parse("2026-08-27T07:30:01Z");

    private final ResearchNarrativePromptBuilder builder =
            new ResearchNarrativePromptBuilder();

    @Test
    @DisplayName("提示词包含正式快照事实和模型安全边界")
    void includesSnapshotFactsAndSafetyBoundaries() {
        ResearchNarrativePrompt prompt = builder.build(
                record("4520.00894962")
        );

        assertThat(prompt.version())
                .isEqualTo("gold-narrative-prompt-v1");
        assertThat(prompt.content())
                .contains("2026-08-21")
                .contains("4520.00894962")
                .contains("2.400000")
                .contains("118.062800")
                .contains("NEUTRAL")
                .contains("SUPPORTIVE")
                .contains("只返回 JSON")
                .contains("不得生成新闻")
                .contains("不得给出目标价")
                .contains("不得给出买入或卖出建议");
        assertThat(prompt.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("相同快照产生稳定摘要而事实变化会改变摘要")
    void createsStableHashFromPromptContent() {
        ResearchNarrativePrompt first = builder.build(
                record("4520.00894962")
        );
        ResearchNarrativePrompt repeated = builder.build(
                record("4520.00894962")
        );
        ResearchNarrativePrompt changed = builder.build(
                record("4521.00000000")
        );

        assertThat(repeated.sha256()).isEqualTo(first.sha256());
        assertThat(changed.sha256()).isNotEqualTo(first.sha256());
    }

    /** 固定数值只验证提示词合同，不代表新的行情或研究结论。 */
    private StoredGoldResearchSnapshot record(String goldPrice) {
        GoldResearchSnapshot snapshot = new GoldResearchSnapshot(
                LocalDate.parse("2026-08-21"),
                LocalDate.parse("2026-08-26"),
                LocalDate.parse("2026-08-25"),
                LocalDate.parse("2026-08-21"),
                new GoldReturnMetrics(
                        new BigDecimal(goldPrice),
                        new BigDecimal("0.1313"),
                        new BigDecimal("3.8413"),
                        new BigDecimal("11.7766"),
                        COLLECTED_AT
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.400000"),
                        new BigDecimal("0.050000"),
                        new BigDecimal("-0.010000"),
                        new BigDecimal("-0.030000"),
                        new BigDecimal("5.00"),
                        new BigDecimal("-1.00"),
                        new BigDecimal("-3.00"),
                        COLLECTED_AT
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118.062800"),
                        new BigDecimal("-0.1624"),
                        new BigDecimal("-0.7065"),
                        new BigDecimal("-2.1934"),
                        COLLECTED_AT
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL,
                        "gold-real-rate-v1",
                        "实际利率变化有限，单因子状态为中性。"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE,
                        "gold-dollar-index-v1",
                        "广义美元指数走弱，对黄金构成单因子支撑。"
                ),
                "gold-multifactor-v2",
                "不构成黄金方向预测或投资建议。"
        );

        return new StoredGoldResearchSnapshot(
                UUID.fromString(
                        "0da5c4c6-81e0-47e8-b016-b9c070830946"
                ),
                snapshot,
                COLLECTED_AT
        );
    }
}
