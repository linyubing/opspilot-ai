package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IncomingMacroObservationTests {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("序列代码不能为空")
    void rejectsBlankSeriesId(String seriesId) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> observation(seriesId, new BigDecimal("1.85")))
                .withMessage("seriesId 不能为空");
    }

    @Test
    @DisplayName("观测值不能为空")
    void rejectsNullValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> observation("DFII10", null))
                .withMessage("value 不能为空");
    }

    /**
     * 固定值只验证领域输入保护，不代表 FRED 的真实实时利率。
     */
    private IncomingMacroObservation observation(
            String seriesId,
            BigDecimal value
    ) {
        return new IncomingMacroObservation(
                seriesId,
                LocalDate.of(2026, 8, 19),
                value,
                "percent",
                "fred"
        );
    }
}
