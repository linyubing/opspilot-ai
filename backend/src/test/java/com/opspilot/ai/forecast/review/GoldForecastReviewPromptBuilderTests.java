package com.opspilot.ai.forecast.review;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.ForecastVersionEvaluation;
import com.opspilot.ai.forecast.GoldForecastEvaluation;
import com.opspilot.ai.forecast.GoldForecastEvaluationService;
import com.opspilot.ai.forecast.GoldForecastRepository;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;
import com.opspilot.ai.forecast.ForecastSignal;
import com.opspilot.ai.forecast.ForecastStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证复盘提示词只使用历史评测事实并执行最小样本门槛。 */
class GoldForecastReviewPromptBuilderTests {

    private final GoldForecastReviewPromptBuilder builder =
            new GoldForecastReviewPromptBuilder();

    @Test @DisplayName("复盘必须知道旧命中率只是历史描述，未知时刻不能证明前瞻准确率")
    void includesPublicationLimits() {
        assertThat(builder.build(eval(40)).content())
                .contains("未知发布时间样本：40", "可信预测样本：0", "可信准确率：暂无有效样本")
                .contains("历史描述", "不能证明未来预测准确率");
    }

    @Test
    @DisplayName("包含评测事实、输出合同和安全边界")
    void includesFactsAndBoundaries() {
        GoldForecastReviewPrompt prompt = builder.build(eval(40));

        assertThat(prompt.version())
                .isEqualTo("gold-forecast-review-prompt-v3");
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

    @Test
    void thirtySettledAbstentionsCannotUnlockReview() {
        assertThatThrownBy(() -> builder.build(signalEvaluation(0, 30, 0)))
                .isInstanceOf(InsufficientForecastReviewSamplesException.class)
                .hasMessageContaining("当前只有 0 条");
    }

    @Test
    void abstentionsCannotFillMissingThirtiethScoredSignal() {
        assertThatThrownBy(() -> builder.build(signalEvaluation(29, 100, 0)))
                .isInstanceOf(InsufficientForecastReviewSamplesException.class)
                .hasMessageContaining("当前只有 29 条");
    }

    @Test
    void reviewExplainsScoredDenominatorAndMissingFullScoreWithoutUpgradingLegacyProtocol() {
        var prompt = builder.build(signalEvaluation(30, 70, 10));
        assertThat(prompt.version()).isEqualTo("gold-forecast-review-prompt-v3");
        assertThat(prompt.content()).contains("已结算评分信号数：30", "显式信号数：40", "弃权数：70",
                "旧未知协议数：0", "显式协议覆盖率：0.3636", "同信号中性基线：0.5000",
                "完整样本准确率：不可得", "弃权不进入命中率分母", "pending", "signal-test-v1");
        assertThat(builder.build(eval(40)).content()).contains("旧汇总缺少信号协议证据", "prompt-v1", "rule-v1");
    }

    private GoldForecastEvaluation signalEvaluation(int scoredCount, int abstainedCount, int pendingSignals) {
        List<StoredGoldDirectionForecast> records = new ArrayList<>();
        for (int i = 0; i < scoredCount + abstainedCount + pendingSignals; i++) {
            boolean abstained = i >= scoredCount && i < scoredCount + abstainedCount;
            boolean pending = i >= scoredCount + abstainedCount;
            var direction = i % 2 == 0 ? ForecastDirection.BULLISH : ForecastDirection.NEUTRAL;
            records.add(new StoredGoldDirectionForecast(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 8, 26),
                    new BigDecimal("2500"), abstained ? null : direction, "测试依据", List.of("测试失效条件"),
                    "test-model", "signal-test-v1", "a".repeat(64), "rule-v1", "测试响应",
                    pending ? ForecastStatus.PENDING : ForecastStatus.RESOLVED,
                    pending ? null : LocalDate.of(2026, 8, 27), pending ? null : new BigDecimal("2500"),
                    pending ? null : BigDecimal.ZERO, pending ? null : direction, pending || abstained ? null : true,
                    pending ? null : OffsetDateTime.parse("2026-08-28T01:00:00Z"),
                    OffsetDateTime.parse("2026-08-27T01:00:00Z"), null,
                    abstained ? ForecastSignal.ABSTAINED : ForecastSignal.PREDICTED));
        }
        var repository = mock(GoldForecastRepository.class);
        when(repository.findAllForEvaluation()).thenReturn(records);
        return new GoldForecastEvaluationService(repository).evaluate();
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
