package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.backtest.BacktestEvaluation;
import com.opspilot.ai.forecast.backtest.ConfusionMatrix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证回测指标能够转换为谨慎、直观的中文结论。 */
class BacktestEvaluationResponseTests {

    @ParameterizedTest
    @MethodSource("conclusions")
    void returnsChineseConclusion(
            int samples,
            String lift,
            String balanced,
            String level,
            String summary
    ) {
        BacktestEvaluationResponse response = BacktestEvaluationResponse.from(
                evaluation(samples, lift, balanced)
        );

        assertThat(response)
                .extracting("conclusion.level")
                .asString()
                .isEqualTo(level);
        assertThat(response)
                .extracting("conclusion.summary")
                .isEqualTo(summary);
    }

    private static Stream<Arguments> conclusions() {
        return Stream.of(
                Arguments.of(
                        20, "0.0500", "0.6000", "INSUFFICIENT",
                        "有效样本不足 30 条，当前结果只能用于观察。"
                ),
                Arguments.of(
                        30, "0.0000", "0.6000", "NO_EDGE",
                        "模型没有超过多数类别基线，暂未发现预测优势。"
                ),
                Arguments.of(
                        30, "0.0600", "0.4900", "UNBALANCED",
                        "模型对不同方向的识别不均衡，暂不具备稳定性。"
                ),
                Arguments.of(
                        30, "0.0300", "0.6000", "WEAK",
                        "模型略高于基线，但提升不足 5 个百分点。"
                ),
                Arguments.of(
                        30, "0.0500", "0.5500", "PROMISING",
                        "模型已超过基线且方向表现较均衡，值得继续扩大样本验证。"
                )
        );
    }

    private BacktestEvaluation evaluation(
            int samples,
            String lift,
            String balanced
    ) {
        DirectionEvaluation empty = new DirectionEvaluation(
                ForecastDirection.BULLISH, 0, 0, null
        );
        return new BacktestEvaluation(
                "BACKTEST", samples, null, null, null,
                null, new BigDecimal(lift), new BigDecimal(balanced),
                ConfusionMatrix.empty(), empty, empty, empty
        );
    }
}
