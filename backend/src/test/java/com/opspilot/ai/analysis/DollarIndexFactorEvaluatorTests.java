package com.opspilot.ai.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DollarIndexFactorEvaluatorTests {

    private final DollarIndexFactorEvaluator evaluator =
            new DollarIndexFactorEvaluator();

    @Test
    @DisplayName("美元因子摘要明确引用5期和20期，不冒充未输入的1期趋势")
    void namesPeriods() {
        var rise = evaluator.evaluate(new BigDecimal("0.8766"), new BigDecimal("2.7579"));
        assertThat(rise.status()).isEqualTo(GoldFactorStatus.PRESSURING);
        assertThat(rise.explanation()).contains("20期涨幅达到1%", "5期收益率为正")
                .doesNotContain("短期继续", "1期", "最新一期");
        var fall = evaluator.evaluate(new BigDecimal("-0.8766"), new BigDecimal("-2.7579"));
        assertThat(fall.status()).isEqualTo(GoldFactorStatus.SUPPORTIVE);
        assertThat(fall.explanation()).contains("20期跌幅达到1%", "5期收益率为负")
                .doesNotContain("短期继续", "1期", "最新一期");
        var conflict = evaluator.evaluate(new BigDecimal("-0.8766"), new BigDecimal("2.7579"));
        assertThat(conflict.status()).isEqualTo(GoldFactorStatus.NEUTRAL);
        assertThat(conflict.explanation()).contains("20期变化有限", "5期与20期方向不一致")
                .doesNotContain("短期", "长期");
    }

    @Test
    @DisplayName("20期上涨达到1%且短期继续上涨时标记为压力")
    void marksSustainedDollarRiseAsPressuring() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("0.10"),
                new BigDecimal("1.00")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.PRESSURING);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("20期下跌达到1%且短期继续下跌时标记为支撑")
    void marksSustainedDollarFallAsSupportive() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("-0.10"),
                new BigDecimal("-1.00")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.SUPPORTIVE);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("中短期方向冲突时保持中性")
    void marksConflictingDirectionsAsNeutral() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("-0.10"),
                new BigDecimal("1.20")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.NEUTRAL);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("20期变化没有达到1%时保持中性")
    void keepsSmallChangesNeutral() {
        ResearchFactorAssessment assessment = evaluator.evaluate(
                new BigDecimal("0.50"),
                new BigDecimal("0.9999")
        );

        assertThat(assessment.status())
                .isEqualTo(GoldFactorStatus.NEUTRAL);
        assertCommonContract(assessment);
    }

    @Test
    @DisplayName("拒绝缺失的周期变化")
    void rejectsMissingChanges() {
        assertThatNullPointerException()
                .isThrownBy(() -> evaluator.evaluate(null, BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() -> evaluator.evaluate(BigDecimal.ZERO, null));
    }

    private void assertCommonContract(ResearchFactorAssessment assessment) {
        assertThat(assessment.ruleVersion())
                .isEqualTo("gold-dollar-index-v2");
        assertThat(assessment.explanation())
                .contains("广义美元指数")
                .contains("黄金");
    }
}
