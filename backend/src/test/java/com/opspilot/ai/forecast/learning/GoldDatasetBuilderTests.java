package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.InsufficientResearchDataException;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoldDatasetBuilderTests {

    private GoldDailyBarRepository repository;
    private GoldResearchSnapshotService snapshots;
    private GoldDatasetBuilder builder;

    @BeforeEach
    void setUp() {
        repository = mock(GoldDailyBarRepository.class);
        snapshots = mock(GoldResearchSnapshotService.class);
        builder = new GoldDatasetBuilder(
                repository,
                snapshots,
                new GoldForecastRule()
        );
    }

    @Test
    @DisplayName("按真实交易日构建下一日样本并计算完整特征")
    void buildsNextDaySamples() {
        List<GoldDailyBar> bars = bars(23);
        when(repository.findAll("XAUUSD", "twelve_data"))
                .thenReturn(bars);
        when(snapshots.createSnapshot(bars.get(20).priceDate()))
                .thenReturn(snapshot(bars.get(20).priceDate()));
        when(snapshots.createSnapshot(bars.get(21).priceDate()))
                .thenReturn(snapshot(bars.get(21).priceDate()));

        GoldDataset dataset = builder.build(ForecastHorizon.NEXT_DAY);

        assertThat(dataset.skippedCount()).isZero();
        assertThat(dataset.samples()).hasSize(2);

        GoldSample sample = dataset.samples().getFirst();
        assertThat(sample.asOfDate()).isEqualTo(bars.get(20).priceDate());
        assertThat(sample.targetDate()).isEqualTo(bars.get(21).priceDate());
        assertThat(sample.horizon()).isEqualTo(ForecastHorizon.NEXT_DAY);
        assertThat(sample.label()).isEqualTo(ForecastDirection.BULLISH);
        assertThat(sample.features().values())
                .containsEntry("gold_return_1", 1.0)
                .containsEntry("gold_volatility_20", 2.5)
                .containsEntry("intraday_range", 2.0)
                .containsEntry("candle_body", 0.5)
                .containsEntry("close_position", 0.75)
                .containsEntry("real_rate", 1.8)
                .containsEntry("real_rate_age", 2.0)
                .containsEntry("dollar_return_20", -2.0)
                .containsEntry("dollar_age", 1.0);
    }

    @Test
    @DisplayName("宏观数据不完整时跳过样本而不是伪造数值")
    void skipsIncompleteMacroData() {
        List<GoldDailyBar> bars = bars(22);
        when(repository.findAll("XAUUSD", "twelve_data"))
                .thenReturn(bars);
        when(snapshots.createSnapshot(bars.get(20).priceDate()))
                .thenThrow(new InsufficientResearchDataException("缺少美元指数"));

        GoldDataset dataset = builder.build(ForecastHorizon.NEXT_DAY);

        assertThat(dataset.samples()).isEmpty();
        assertThat(dataset.skippedCount()).isOne();
    }

    @Test
    @DisplayName("五日目标严格使用第五个后续交易日")
    void usesTradingDayHorizon() {
        List<GoldDailyBar> bars = bars(26);
        when(repository.findAll("XAUUSD", "twelve_data"))
                .thenReturn(bars);
        when(snapshots.createSnapshot(bars.get(20).priceDate()))
                .thenReturn(snapshot(bars.get(20).priceDate()));

        GoldDataset dataset = builder.build(ForecastHorizon.FIVE_DAYS);

        assertThat(dataset.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.asOfDate()).isEqualTo(bars.get(20).priceDate());
            assertThat(sample.targetDate()).isEqualTo(bars.get(25).priceDate());
        });
    }

    @Test
    @DisplayName("拒绝使用分析日之后发布的宏观数据")
    void rejectsFutureMacroData() {
        List<GoldDailyBar> bars = bars(22);
        LocalDate date = bars.get(20).priceDate();
        when(repository.findAll("XAUUSD", "twelve_data"))
                .thenReturn(bars);
        when(snapshots.createSnapshot(date))
                .thenReturn(snapshot(date, date.plusDays(1), date));

        assertThatThrownBy(() -> builder.build(ForecastHorizon.NEXT_DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("宏观特征不能来自分析日期之后");
    }

    private GoldResearchSnapshot snapshot(LocalDate date) {
        return snapshot(date, date.minusDays(2), date.minusDays(1));
    }

    private GoldResearchSnapshot snapshot(
            LocalDate date,
            LocalDate rateDate,
            LocalDate dollarDate
    ) {
        OffsetDateTime collectedAt = date.atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        return new GoldResearchSnapshot(
                date,
                date,
                rateDate,
                dollarDate,
                new GoldReturnMetrics(
                        new BigDecimal("2000"),
                        new BigDecimal("1"),
                        new BigDecimal("3"),
                        new BigDecimal("8"),
                        new BigDecimal("2.5"),
                        collectedAt
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("1.8"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.03"),
                        new BigDecimal("0.05"),
                        new BigDecimal("1"),
                        new BigDecimal("3"),
                        new BigDecimal("5"),
                        collectedAt
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("120"),
                        new BigDecimal("-0.2"),
                        new BigDecimal("-0.8"),
                        new BigDecimal("-2"),
                        collectedAt
                ),
                null,
                null,
                "test",
                "test"
        );
    }

    private List<GoldDailyBar> bars(int count) {
        List<GoldDailyBar> result = new ArrayList<>();
        LocalDate date = LocalDate.parse("2026-01-01");
        for (int i = 0; i < count; i++) {
            BigDecimal open = new BigDecimal("100").add(BigDecimal.valueOf(i));
            result.add(new GoldDailyBar(
                    "XAUUSD",
                    date.plusDays(i),
                    open,
                    open.multiply(new BigDecimal("1.01")),
                    open.multiply(new BigDecimal("0.99")),
                    open.multiply(new BigDecimal("1.005")),
                    "USD",
                    "troy_ounce",
                    "twelve_data",
                    date.plusDays(i).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
            ));
        }
        return result;
    }
}
