package com.opspilot.ai.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 验证黄金真实涨跌幅的三分类边界规则。
 */
class GoldForecastRuleTests {

    private final GoldForecastRule rule = new GoldForecastRule();

    @DisplayName("按严格边界把真实涨跌幅划分为上涨、中性或下跌")
    @ParameterizedTest(name = "涨跌幅 {0}% 应归类为 {1}")
    @MethodSource("directionCases")
    void classifiesDirection(String actualReturn, ForecastDirection expected) {
        assertThat(rule.classify(new BigDecimal(actualReturn))).isEqualTo(expected);
    }

    @Test
    @DisplayName("拒绝缺失的真实涨跌幅")
    void rejectsMissingReturn() {
        assertThatNullPointerException()
                .isThrownBy(() -> rule.classify(null));
    }

    /**
     * 期望值使用人工确认的字面量，避免复制生产代码的比较逻辑。
     */
    private static Stream<Arguments> directionCases() {
        return Stream.of(
                Arguments.of("0.500001", ForecastDirection.BULLISH),
                Arguments.of("0.500000", ForecastDirection.NEUTRAL),
                Arguments.of("0", ForecastDirection.NEUTRAL),
                Arguments.of("-0.500000", ForecastDirection.NEUTRAL),
                Arguments.of("-0.500001", ForecastDirection.BEARISH)
        );
    }
}
