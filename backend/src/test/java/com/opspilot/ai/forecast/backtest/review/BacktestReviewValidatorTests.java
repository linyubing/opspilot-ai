package com.opspilot.ai.forecast.backtest.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 AI 回测复盘只能引用当前任务允许的真实错误样本。 */
class BacktestReviewValidatorTests {

    private static final String CASE_ID =
            "11111111-1111-1111-1111-111111111111";

    private final BacktestReviewValidator validator =
            new BacktestReviewValidator();

    @Test
    void acceptsCompleteReview() {
        validator.validate(content(List.of(CASE_ID)), Set.of(CASE_ID));
    }

    @Test
    void rejectsMissingOrUnknownEvidence() {
        assertThatThrownBy(() -> validator.validate(
                content(List.of()),
                Set.of(CASE_ID)
        )).isInstanceOf(InvalidBacktestReviewAiResponseException.class);

        assertThatThrownBy(() -> validator.validate(
                content(List.of("99999999-9999-9999-9999-999999999999")),
                Set.of(CASE_ID)
        )).isInstanceOf(InvalidBacktestReviewAiResponseException.class);
    }

    @Test
    void rejectsEmptyContract() {
        assertThatThrownBy(() -> validator.validate(
                new BacktestReviewContent(null, null, null, null, null),
                Set.of(CASE_ID)
        )).isInstanceOf(InvalidBacktestReviewAiResponseException.class);
    }

    @Test
    void rejectsNullEvidence() {
        assertThatThrownBy(() -> validator.validate(
                content(Arrays.asList((String) null)),
                Set.of(CASE_ID)
        )).isInstanceOf(InvalidBacktestReviewAiResponseException.class);
    }

    private BacktestReviewContent content(List<String> evidence) {
        return new BacktestReviewContent(
                "趋势反转日错误较多",
                evidence,
                List.of(new BacktestErrorPattern(
                        "趋势延续误判",
                        "趋势反转后仍然看涨",
                        evidence,
                        "增加趋势衰减条件",
                        "使用下一批历史样本验证"
                )),
                List.of(new BacktestReviewRisk("样本有限", evidence)),
                "不构成投资建议"
        );
    }
}
