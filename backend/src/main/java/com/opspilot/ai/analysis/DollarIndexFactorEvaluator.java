package com.opspilot.ai.analysis;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Objects;

/** 根据广义美元指数的短期和中期变化评估其对黄金的单因子影响。 */
@Component
public class DollarIndexFactorEvaluator {

    /**
     * 20期美元指数变化达到该比例时，
     * 才认为美元存在较明确的中期趋势。
     */
    private static final BigDecimal THRESHOLD_PERCENT =
            new BigDecimal("1.0000");

    private static final String RULE_VERSION =
            "gold-dollar-index-v1";

    public ResearchFactorAssessment evaluate(
            BigDecimal return5,
            BigDecimal return20
    ) {
        Objects.requireNonNull(
                return5,
                "5期美元指数变化不能为空"
        );
        Objects.requireNonNull(
                return20,
                "20期美元指数变化不能为空"
        );

        /*
         * 美元中期上涨至少1%，并且短期仍在上涨，
         * 说明美元保持强势，对以美元计价的黄金构成压力。
         */
        if (return20.compareTo(THRESHOLD_PERCENT) >= 0
                && return5.compareTo(BigDecimal.ZERO) > 0) {
            return assessment(
                    GoldFactorStatus.PRESSURING,
                    "广义美元指数中期明显走强且短期继续走强，"
                            + "对黄金构成单因子压力。"
            );
        }

        /*
         * 美元中期下跌至少1%，并且短期仍在下跌，
         * 说明美元持续走弱，对黄金形成支撑。
         */
        if (return20.compareTo(
                THRESHOLD_PERCENT.negate()
        ) <= 0
                && return5.compareTo(BigDecimal.ZERO) < 0) {
            return assessment(
                    GoldFactorStatus.SUPPORTIVE,
                    "广义美元指数中期明显走弱且短期继续走弱，"
                            + "对黄金构成单因子支撑。"
            );
        }

        return assessment(
                GoldFactorStatus.NEUTRAL,
                "广义美元指数变化有限或长短周期方向不一致，"
                        + "对黄金的单因子状态为中性。"
        );
    }

    private ResearchFactorAssessment assessment(
            GoldFactorStatus status,
            String explanation
    ) {
        return new ResearchFactorAssessment(
                status,
                RULE_VERSION,
                explanation
        );
    }
}
