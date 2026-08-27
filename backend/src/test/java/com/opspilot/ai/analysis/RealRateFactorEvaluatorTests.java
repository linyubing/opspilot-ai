package com.opspilot.ai.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RealRateFactorEvaluatorTests {

    private final RealRateFactorEvaluator evaluator =
            new RealRateFactorEvaluator();

    @Test
    @DisplayName("20 期至少上升 10 个基点且短期继续上升时标记为压力因素")
    void marksSustainedRateRiseAsPressuring() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("1.00"),
                new BigDecimal("10.00")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.PRESSURING);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("20 期至少下降 10 个基点且短期继续下降时标记为支撑因素")
    void marksSustainedRateFallAsSupportive() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("-1.00"),
                new BigDecimal("-10.00")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.SUPPORTIVE);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("中期上升但短期下降时标记为中性")
    void marksConflictingDirectionsAsNeutral() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("-1.00"),
                new BigDecimal("12.00")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.NEUTRAL);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("短期没有变化时不把中期上升直接标记为压力")
    void requiresShortTermConfirmation() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                BigDecimal.ZERO,
                new BigDecimal("10.00")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.NEUTRAL);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("未达到 10 个基点阈值时保持中性")
    void keepsSmallChangesNeutral() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("5.00"),
                new BigDecimal("9.99")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.NEUTRAL);
        assertCommonContract(assessment);
    }

    private void assertCommonContract(
            ResearchFactorAssessment assessment
    ) {
        assertThat(assessment.ruleVersion())
                .isEqualTo("gold-real-rate-v1");
        assertThat(assessment.explanation())
                .contains("实际利率");
    }
}
