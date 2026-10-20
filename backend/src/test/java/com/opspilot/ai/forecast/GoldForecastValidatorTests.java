package com.opspilot.ai.forecast;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 验证方向预测结构和禁止内容的安全边界。 */
class GoldForecastValidatorTests {

    @Test
    @DisplayName("弃权方向必须为空，不把证据不足伪装成中性")
    void validatesAbstentionContract() {
        var validator = new GoldForecastValidator();
        assertThatCode(() -> validator.validateFormal(new GoldDirectionForecastContent(null,
                "方向证据不足，暂不出信号。", List.of("取得更多已知数据后重新判断"), ForecastSignal.ABSTAINED)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateFormal(new GoldDirectionForecastContent(ForecastDirection.NEUTRAL,
                "证据不足", List.of("条件"), ForecastSignal.ABSTAINED)))
                .isInstanceOf(UnsafeGoldForecastException.class);
        assertThatThrownBy(() -> validator.validateFormal(new GoldDirectionForecastContent(ForecastDirection.NEUTRAL,
                "真实区间依据", List.of("条件"))))
                .isInstanceOf(UnsafeGoldForecastException.class).hasMessageContaining("信号状态");
        assertThatThrownBy(() -> validator.validateFormal(new GoldDirectionForecastContent(null,
                "方向证据", List.of("条件"), ForecastSignal.PREDICTED)))
                .isInstanceOf(UnsafeGoldForecastException.class);
    }

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

    @Test
    @DisplayName("允许描述真实涨跌幅和方向判定阈值")
    void acceptsPriceChangePercentages() {
        assertThatCode(() -> validator.validate(
                new GoldDirectionForecastContent(
                        ForecastDirection.BULLISH,
                        "黄金20期上涨11.7766%，美元指数走弱形成支撑。",
                        List.of("若下一有效交易日上涨不超过0.5%，方向判断失效")
                )
        )).doesNotThrowAnyException();
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
