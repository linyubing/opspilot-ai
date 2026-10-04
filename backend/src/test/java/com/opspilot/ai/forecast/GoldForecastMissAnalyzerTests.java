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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "2026-08-28T00:30:00+08:00,2026-08-20,direction_mismatch",
            "2026-08-28T00:30:00Z,2026-08-20,stale_macro_data",
            "2026-08-27T01:00:00Z,2026-08-19,stale_macro_data"})
    @DisplayName("诊断用真实UTC发布日而非本地日期或快照分析日，8日才越界")
    void usesUtcPublication(String published, String dollarDate, String expected) {
        var input = snapshot(returnMetrics("2500", "0.1", "0.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-26", dollarDate));
        var result = analyzer.analyze(published(OffsetDateTime.parse(published)), input);
        assertThat(result.code()).isEqualTo(expected);
    }

    @Test @DisplayName("未提供美元观测日期时不把可选缺席说成已过期")
    void ignoresMissingDollar() {
        var input = snapshot(returnMetrics("2500", "0.1", "0.5", "12"),
                new LocalDate[]{LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-27"),
                        LocalDate.parse("2026-08-26"), null});
        assertThat(analyzer.analyze(published(OffsetDateTime.parse("2026-08-27T01:00:00Z")), input).code())
                .isEqualTo("direction_mismatch");
    }

    @Test @DisplayName("缺少发布时间时不猜测当时宏观年龄，保留分类未命中事实")
    void ignoresUnknownPublication() {
        var input = snapshot(returnMetrics("2500", "0.1", "0.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-01", "2026-08-01"));
        assertThat(analyzer.analyze(published(null), input).code()).isEqualTo("direction_mismatch");
    }

    @Test @DisplayName("宏观滞后诊断展示具体美元观测日、UTC发布日和8天年龄")
    void showsMacroEvidence() {
        var input = snapshot(returnMetrics("2500", "0.1", "0.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-26", "2026-08-20"));
        var result = analyzer.analyze(published(OffsetDateTime.parse("2026-08-28T00:30:00Z")), input);
        assertThat(result.detail()).contains("2026-08-28", "2026-08-20", "8天", "广义美元指数", "不能证明");
    }

    private StoredGoldDirectionForecast published(OffsetDateTime created) {
        var old = forecast(false, ForecastDirection.BULLISH, new BigDecimal("-1"), ForecastDirection.BEARISH, null);
        return new StoredGoldDirectionForecast(old.id(), old.snapshotId(), old.baseDate(), old.basePrice(),
                old.predictedDirection(), old.reasoning(), old.invalidationConditions(), old.modelName(),
                old.promptVersion(), old.promptHash(), old.forecastRuleVersion(), old.rawResponse(), old.status(),
                old.targetDate(), old.targetPrice(), old.actualReturn(), old.actualDirection(), old.hit(), old.resolvedAt(), created);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "NEUTRAL,BULLISH,0.8", "NEUTRAL,BEARISH,-0.8",
            "BULLISH,NEUTRAL,0.2", "BEARISH,NEUTRAL,-0.2"})
    @DisplayName("中性与涨跌误判都有结算事实，不能遗漏非反向失败")
    void explainsNeutralMiss(ForecastDirection predicted, ForecastDirection actual, String move) {
        var input = snapshot(returnMetrics("2500", "0.1", "0.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-26", "2026-08-26"));
        var result = analyzer.analyze(forecast(false, predicted, new BigDecimal(move), actual, null), input);
        assertThat(result).isNotNull();
        assertThat(result.code()).isEqualTo("direction_mismatch");
        assertThat(result.detail()).contains(predicted.name(), actual.name(), move);
    }

    @Test @DisplayName("趋势符号冲突只能提供线索，不能证明大模型依赖某个权重")
    void limitsTrendClaim() {
        var input = snapshot(returnMetrics("2500", "-0.3", "5.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-26", "2026-08-26"));
        var result = analyzer.analyze(forecast(false, ForecastDirection.BULLISH,
                new BigDecimal("-1"), ForecastDirection.BEARISH, null), input);
        assertThat(result.detail()).contains("-0.3", "5.5", "不能证明");
        assertThat(result.detail()).doesNotContain("模型过度依赖");
    }

    @Test @DisplayName("大幅反向收益不是突发新闻证据，诊断不能编造事件原因")
    void limitsEventClaim() {
        var input = snapshot(returnMetrics("2500", "0.3", "5.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-26", "2026-08-26"));
        var result = analyzer.analyze(forecast(false, ForecastDirection.BULLISH,
                new BigDecimal("-2.4"), ForecastDirection.BEARISH, null), input);
        assertThat(result.detail()).contains("-2.4", "不能证明");
        assertThat(result.title()).doesNotContain("突发");
    }

    @Test @DisplayName("广义美元指数第7自然日不被诊断成超过统一7日观察口径")
    void acceptsSevenDayDollarLag() {
        var input = snapshot(returnMetrics("2500", "0.3", "5.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-26", "2026-08-20"));
        var result = analyzer.analyze(forecast(false, ForecastDirection.BULLISH,
                new BigDecimal("-1"), ForecastDirection.BEARISH, null), input);
        assertThat(result.code()).isEqualTo("direction_mismatch");
    }

    @Test @DisplayName("命中标记尚未确认时不进行失败诊断")
    void requiresSettledStatus() {
        var old = forecast(false, ForecastDirection.BULLISH, new BigDecimal("-1"), ForecastDirection.BEARISH, null);
        var pending = new StoredGoldDirectionForecast(old.id(), old.snapshotId(), old.baseDate(), old.basePrice(),
                old.predictedDirection(), old.reasoning(), old.invalidationConditions(), old.modelName(),
                old.promptVersion(), old.promptHash(), old.forecastRuleVersion(), old.rawResponse(), ForecastStatus.PENDING,
                old.targetDate(), old.targetPrice(), old.actualReturn(), old.actualDirection(), old.hit(), old.resolvedAt(), old.createdAt());
        var input = snapshot(returnMetrics("2500", "0.3", "5.5", "12"),
                dates("2026-08-27", "2026-08-27", "2026-08-26", "2026-08-26"));
        assertThat(analyzer.analyze(pending, input)).isNull();
    }

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
                // 特意保留相反方向：移除hit=true门禁时必须真的失败。
                forecast(true, ForecastDirection.BULLISH, new BigDecimal("-1"), ForecastDirection.BEARISH, null);
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
