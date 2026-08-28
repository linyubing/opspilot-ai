package com.opspilot.ai.forecast.review;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.ForecastVersionEvaluation;
import com.opspilot.ai.forecast.GoldForecastEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证复盘提示词只使用历史评测事实并执行最小样本门槛。 */
class GoldForecastReviewPromptBuilderTests {

    private final GoldForecastReviewPromptBuilder builder =
            new GoldForecastReviewPromptBuilder();

    @Test
    @DisplayName("包含评测事实、输出合同和安全边界")
    void includesFactsAndBoundaries() {
        GoldForecastReviewPrompt prompt = builder.build(eval(40));

        assertThat(prompt.version())
                .isEqualTo("gold-forecast-review-prompt-v1");
        assertThat(prompt.content())
                .contains("已解析样本数：40")
                .contains("总体命中率：0.6250")
                .contains("最近20条命中率：0.5500")
                .contains("BULLISH：样本数=20，命中数=14")
                .contains("glm-4.7", "prompt-v1", "rule-v1")
                .contains("不得编造", "不得自动修改正式预测提示词")
                .contains("只返回 JSON");
    }

    @Test
    @DisplayName("已解析样本不足三十条时拒绝复盘")
    void rejectsSmallSample() {
        assertThatThrownBy(() -> builder.build(eval(29)))
                .isInstanceOf(
                        InsufficientForecastReviewSamplesException.class
                )
                .hasMessageContaining("至少需要 30 条")
                .hasMessageContaining("当前只有 29 条");
    }

    private GoldForecastEvaluation eval(int resolvedCount) {
        return new GoldForecastEvaluation(
                resolvedCount + 5,
                5,
                resolvedCount,
                new BigDecimal("0.6250"),
                direction(ForecastDirection.BULLISH, 20, 14, "0.7000"),
                direction(ForecastDirection.NEUTRAL, 10, 6, "0.6000"),
                direction(ForecastDirection.BEARISH, 10, 5, "0.5000"),
                new BigDecimal("0.5500"),
                new BigDecimal("0.3000"),
                List.of(new ForecastVersionEvaluation(
                        "glm-4.7", "prompt-v1", "rule-v1",
                        resolvedCount, 25, new BigDecimal("0.6250")
                ))
        );
    }

    private DirectionEvaluation direction(
            ForecastDirection direction,
            int samples,
            int hits,
            String accuracy
    ) {
        return new DirectionEvaluation(
                direction,
                samples,
                hits,
                new BigDecimal(accuracy)
        );
    }
}
