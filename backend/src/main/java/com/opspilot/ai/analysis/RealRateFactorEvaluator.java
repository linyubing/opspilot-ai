package com.opspilot.ai.analysis;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 根据实际利率的短期和中期变化评估单因子状态。
 */
@Component
public class RealRateFactorEvaluator {

    private static final BigDecimal THRESHOLD_BASIS_POINTS =
            new BigDecimal("10");

    private static final String RULE_VERSION =
            "gold-real-rate-v1";


    public ResearchFactorAssessment evaluate(
            BigDecimal change5BasisPoints,
            BigDecimal change20BasisPoints
    ) {
        Objects.requireNonNull(
                change5BasisPoints,
                "5 期基点变化不能为空"
        );
        Objects.requireNonNull(
                change20BasisPoints,
                "20 期基点变化不能为空"
        );

        /*
         * 中期至少上升 10 个基点，并且短期仍在上升，
         * 才认为实际利率对黄金构成持续压力。
         */
        if (change20BasisPoints.compareTo(
                THRESHOLD_BASIS_POINTS
        ) >= 0
                && change5BasisPoints.compareTo(
                BigDecimal.ZERO
        ) > 0) {
            return assessment(
                    RealRateFactorStatus.PRESSURING,
                    "实际利率中期明显上升且短期继续上升，"
                            + "对黄金构成单因子压力。"
            );
        }

        /*
         * 中期至少下降 10 个基点，并且短期仍在下降，
         * 才认为实际利率对黄金构成持续支撑。
         */
        if (change20BasisPoints.compareTo(
                THRESHOLD_BASIS_POINTS.negate()
        ) <= 0
                && change5BasisPoints.compareTo(
                BigDecimal.ZERO
        ) < 0) {
            return assessment(
                    RealRateFactorStatus.SUPPORTIVE,
                    "实际利率中期明显下降且短期继续下降，"
                            + "对黄金构成单因子支撑。"
            );
        }

        return assessment(
                RealRateFactorStatus.NEUTRAL,
                "实际利率变化有限或长短周期方向不一致，"
                        + "单因子状态为中性。"
        );
    }

    private ResearchFactorAssessment assessment(
            RealRateFactorStatus status,
            String explanation
    ) {
        return new ResearchFactorAssessment(
                status,
                RULE_VERSION,
                explanation
        );
    }
}
