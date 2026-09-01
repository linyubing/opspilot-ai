package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证失败预测的归因规则与优先级。 */
class GoldForecastMissAnalyzerTests {

    private final GoldForecastMissAnalyzer analyzer = new GoldForecastMissAnalyzer();

    @Test
    @DisplayName("未结算的预测不产生失败原因")
    void returnsNullWhenNotSettled() {
        GoldResearchSnapshot snapshot = snapshot(
                returnMetrics("0.25", "0.10", "5.50", "12.00"),
                dates("2026-08-21", "2026-08-26", "2026-08-25", "2026-08-21")
        );
        StoredGoldDirectionForecast forecast =
                forecast(false, ForecastDirection.BULLISH, null, null, null);
        assertThat(analyzer.analyze(forecast, snapshot)).isNull();
    }

    @Test
    @DisplayName("命中预测不产生失败原因")
    void returnsNullWhenHit() {
        GoldResearchSnapshot snapshot = snapshot(
                returnMetrics("0.25", "0.10", "5.50", "12.00"),
                dates("2026-08-21", "2026-08-26", "2026-08-25", "2026-08-21")
        );
        StoredGoldDirectionForecast forecast =
                forecast(false, ForecastDirection.BULLISH, null, ForecastDirection.BULLISH, new BigDecimal("1.00"));
        assertThat(analyzer.analyze(forecast, snapshot)).isNull();
    }

    @Test
    @DisplayName("中期趋势强但短期转弱时识别趋势权重过高")
    void identifiesTrendWeightTooHighWhenShortTermMomentumLost() {
        GoldResearchSnapshot snapshot = snapshot(
                returnMetrics("0.25", "-0.30", "5.50", "12.00"),
                dates("2026-08-21", "2026-08-26", "2026-08-25", "2026-08-21")
        );
        StoredGoldDirectionForecast forecast =
                forecast(false, ForecastDirection.BULLISH, new BigDecimal("1.20"),
                        ForecastDirection.BEARISH, new BigDecimal("-1.00"));

        GoldForecastMissReason reason = analyzer.analyze(forecast, snapshot);

        assertThat(reason).isNotNull();
        assertThat(reason.code()).isEqualTo("trend_weight_too_high");
        assertThat(reason.tags()).containsExactly(
                "shortTermMomentumLoss", "midTermTrendStrong"
        );
    }

    @Test
    @DisplayName("实际反向波动达到2%以上时识别突发市场波动")
    void identifiesUnexpectedMarketMoveWhenLargeOppositeMove() {
        GoldResearchSnapshot snapshot = snapshot(
                returnMetrics("0.25", "0.10", "5.50", "12.00"),
                dates("2026-08-21", "2026-08-26", "2026-08-25", "2026-08-21")
        );
        StoredGoldDirectionForecast forecast =
                forecast(false, ForecastDirection.BULLISH, new BigDecimal("2.40"),
                        ForecastDirection.BEARISH, new BigDecimal("-2.40"));

        GoldForecastMissReason reason = analyzer.analyze(forecast, snapshot);

        assertThat(reason).isNotNull();
        assertThat(reason.code()).isEqualTo("unexpected_market_move");
    }

    @Test
    @DisplayName("宏观数据超过允许年龄时识别宏观滞后")
    void identifiesStaleMacroData() {
        GoldResearchSnapshot snapshot = snapshot(
                returnMetrics("0.25", "0.10", "5.50", "12.00"),
                dates("2026-08-21", "2026-08-26", "2026-08-10", "2026-08-10")
        );
        StoredGoldDirectionForecast forecast =
                forecast(false, ForecastDirection.BULLISH, new BigDecimal("1.00"),
                        ForecastDirection.BEARISH, new BigDecimal("-1.00"));

        GoldForecastMissReason reason = analyzer.analyze(forecast, snapshot);

        assertThat(reason).isNotNull();
        assertThat(reason.code()).isEqualTo("stale_macro_data");
    }

    @Test
    @DisplayName("高波动环境下识别高波动归因")
    void identifiesHighVolatility() {
        GoldResearchSnapshot snapshot = snapshot(
                returnMetrics("0.25", "0.10", "5.50", "25.00"),
                dates("2026-08-21", "2026-08-26", "2026-08-25", "2026-08-21")
        );
        StoredGoldDirectionForecast forecast =
                forecast(false, ForecastDirection.BULLISH, new BigDecimal("1.00"),
                        ForecastDirection.BEARISH, new BigDecimal("-1.00"));

        GoldForecastMissReason reason = analyzer.analyze(forecast, snapshot);

        assertThat(reason).isNotNull();
        assertThat(reason.code()).isEqualTo("high_volatility");
    }

    @Test
    @DisplayName("常规反向归因为没有空白标签")
    void directionMismatchHasNoBlankTags() {
        GoldResearchSnapshot snapshot = snapshot(
                returnMetrics("0.25", "0.10", "5.50", "12.00"),
                dates("2026-08-21", "2026-08-26", "2026-08-25", "2026-08-21")
        );
        StoredGoldDirectionForecast forecast =
                forecast(false, ForecastDirection.BULLISH, new BigDecimal("0.60"),
                        ForecastDirection.BEARISH, new BigDecimal("-0.60"));

        GoldForecastMissReason reason = analyzer.analyze(forecast, snapshot);

        assertThat(reason).isNotNull();
        assertThat(reason.code()).isEqualTo("direction_mismatch");
        assertThat(reason.tags()).isNotEmpty();
        assertThat(reason.tags()).noneMatch(String::isBlank);
        assertThat(reason.detail()).isNotBlank();
    }

    private StoredGoldDirectionForecast forecast(
            boolean hit, ForecastDirection predicted,
            BigDecimal actualReturn, ForecastDirection actualDirection,
            BigDecimal unused
    ) {
        return new StoredGoldDirectionForecast(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                GoldForecastTestFixtures.SNAPSHOT_ID,
                LocalDate.parse("2026-08-27"), new BigDecimal("2500.000000"),
                predicted, "依据", List.of("条件"), "glm-4.7",
                GoldForecastPromptBuilder.PROMPT_VERSION, "a".repeat(64),
                GoldForecastRule.RULE_VERSION, "敏感原始响应",
                ForecastStatus.RESOLVED, LocalDate.parse("2026-08-28"),
                new BigDecimal("2504.000000"), actualReturn,
                actualDirection, hit, OffsetDateTime.parse("2026-08-28T01:00:00Z"),
                OffsetDateTime.parse("2026-08-27T01:00:00Z")
        );
    }

    private GoldResearchSnapshot snapshot(
            GoldReturnMetrics gold, LocalDate[] dates
    ) {
        return new GoldResearchSnapshot(
                dates[0], dates[1], dates[2], dates[3],
                gold,
                new RealRateChangeMetrics(
                        new BigDecimal("2.4"), new BigDecimal("0.05"),
                        new BigDecimal("-0.01"), new BigDecimal("-0.03"),
                        new BigDecimal("5.00"), new BigDecimal("-1.00"),
                        new BigDecimal("-3.00"),
                        OffsetDateTime.parse("2026-08-21T07:30:01Z")
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118.06"), new BigDecimal("-0.16"),
                        new BigDecimal("-0.71"), new BigDecimal("-2.19"),
                        OffsetDateTime.parse("2026-08-21T07:30:01Z")
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "gold-real-rate-v1", "实际利率变化有限。"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE, "gold-dollar-index-v1", "美元指数走弱。"
                ),
                "gold-multifactor-v2", "不构成投资建议。"
        );
    }

    private GoldReturnMetrics returnMetrics(
            String price, String return1, String return20, String volatility20
    ) {
        return new GoldReturnMetrics(
                new BigDecimal(price), new BigDecimal(return1),
                new BigDecimal("0.50"), new BigDecimal(return20),
                new BigDecimal(volatility20),
                OffsetDateTime.parse("2026-08-21T07:30:01Z")
        );
    }

    private LocalDate[] dates(
            String analysis, String gold, String rate, String dollar
    ) {
        return new LocalDate[]{
                LocalDate.parse(analysis), LocalDate.parse(gold),
                LocalDate.parse(rate), LocalDate.parse(dollar)
        };
    }
}
