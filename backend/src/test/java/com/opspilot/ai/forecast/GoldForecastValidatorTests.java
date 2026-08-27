package com.opspilot.ai.forecast;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 验证方向预测结构和禁止内容的安全边界。 */
class GoldForecastValidatorTests {

    private final GoldForecastValidator validator = new GoldForecastValidator();

    @Test
    @DisplayName("接受字段完整且不包含交易指令的预测")
    void acceptsSafeForecast() {
        assertThatCode(() -> validator.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝缺失方向或空白研究依据")
    void rejectsMissingRequiredFields() {
        assertUnsafe(new GoldDirectionForecastContent(null, "依据", List.of("条件")));
        assertUnsafe(new GoldDirectionForecastContent(
                ForecastDirection.NEUTRAL, "  ", List.of("条件")
        ));
    }

    @Test
    @DisplayName("拒绝数量或长度越界的失效条件")
    void rejectsInvalidConditions() {
        assertUnsafe(new GoldDirectionForecastContent(
                ForecastDirection.NEUTRAL, "依据", List.of()
        ));
        assertUnsafe(new GoldDirectionForecastContent(
                ForecastDirection.NEUTRAL, "依据", List.of("条件".repeat(151))
        ));
    }

    @Test
    @DisplayName("拒绝交易指令、目标价格和仓位")
    void rejectsTradingInstructions() {
        for (String phrase : List.of("建议买入", "建议卖出", "目标价", "止损位", "仓位")) {
            assertUnsafe(new GoldDirectionForecastContent(
                    ForecastDirection.BULLISH, "研究依据包含" + phrase, List.of("条件")
            ));
        }
    }

    @Test
    @DisplayName("拒绝数值化涨跌概率")
    void rejectsNumericProbability() {
        assertUnsafe(new GoldDirectionForecastContent(
                ForecastDirection.BULLISH, "上涨概率为 70%", List.of("条件")
        ));
    }

    private GoldDirectionForecastContent valid() {
        return new GoldDirectionForecastContent(
                ForecastDirection.NEUTRAL,
                "实际利率中性，美元指数对黄金形成支撑。",
                List.of("实际利率明显上升", "美元指数转强")
        );
    }

    private void assertUnsafe(GoldDirectionForecastContent content) {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOf(UnsafeGoldForecastException.class);
    }
}
