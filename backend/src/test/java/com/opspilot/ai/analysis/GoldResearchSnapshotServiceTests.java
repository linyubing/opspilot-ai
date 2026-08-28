package com.opspilot.ai.analysis;

import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldResearchSnapshotServiceTests {

    private static final LocalDate ANALYSIS_DATE =
            LocalDate.parse("2026-08-24");
    private static final OffsetDateTime GOLD_COLLECTED_AT =
            OffsetDateTime.parse("2026-08-26T01:00:00Z");
    private static final OffsetDateTime RATE_COLLECTED_AT =
            OffsetDateTime.parse("2026-08-26T02:00:00Z");

    private MarketPriceRepository marketPriceRepository;
    private MacroObservationRepository macroObservationRepository;
    private GoldResearchSnapshotService service;

    @BeforeEach
    void setUp() {
        marketPriceRepository = mock(MarketPriceRepository.class);
        macroObservationRepository =
                mock(MacroObservationRepository.class);
        service = new GoldResearchSnapshotService(
                marketPriceRepository,
                macroObservationRepository,
                new RealRateFactorEvaluator(),
                new DollarIndexFactorEvaluator()
        );
        when(macroObservationRepository.findRecent("DTWEXBGS", 120))
                .thenReturn(dollarIndexes(21));
    }

    @Test
    @DisplayName("按最新共同日期计算可复算的 1、5、20 期指标")
    void calculatesMetricsFromCommonDates() {
        // 固定数值只验证算法，不代表真实行情。
        List<MarketPrice> goldPrices = goldPrices(21);
        goldPrices.add(goldPrice(
                LocalDate.parse("2026-08-25"),
                "2300"
        ));
        Collections.rotate(goldPrices, 7);

        List<MacroObservation> realRates = realRates(21);
        realRates.add(realRate(
                LocalDate.parse("2026-08-26"),
                "2.40"
        ));
        Collections.reverse(realRates);

        List<MacroObservation> dollarIndexes = dollarIndexes(21);
        dollarIndexes.add(dollarIndex(
                LocalDate.parse("2026-08-25"),
                "121.00"
        ));
        Collections.rotate(dollarIndexes, 3);

        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(goldPrices);
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates);
        when(macroObservationRepository.findRecent("DTWEXBGS", 120))
                .thenReturn(dollarIndexes);

        GoldResearchSnapshot snapshot = service.createSnapshot();

        assertThat(snapshot.analysisDate()).isEqualTo(ANALYSIS_DATE);
        assertThat(snapshot.latestGoldDate())
                .isEqualTo(LocalDate.parse("2026-08-25"));
        assertThat(snapshot.latestRealRateDate())
                .isEqualTo(LocalDate.parse("2026-08-26"));
        assertThat(snapshot.latestDollarIndexDate())
                .isEqualTo(LocalDate.parse("2026-08-25"));
        assertThat(snapshot.gold().currentPrice())
                .isEqualByComparingTo("2200");
        assertThat(snapshot.gold().return1())
                .isEqualByComparingTo("10.0000");
        assertThat(snapshot.gold().return5())
                .isEqualByComparingTo("4.7619");
        assertThat(snapshot.gold().return20())
                .isEqualByComparingTo("22.2222");
        assertThat(snapshot.gold().collectedAt())
                .isEqualTo(GOLD_COLLECTED_AT);

        assertThat(snapshot.realRate().currentRate())
                .isEqualByComparingTo("2.38");
        assertThat(snapshot.realRate().percentagePointChange1())
                .isEqualByComparingTo("0.080000");
        assertThat(snapshot.realRate().percentagePointChange5())
                .isEqualByComparingTo("0.180000");
        assertThat(snapshot.realRate().percentagePointChange20())
                .isEqualByComparingTo("0.280000");
        assertThat(snapshot.realRate().basisPointChange1())
                .isEqualByComparingTo("8.00");
        assertThat(snapshot.realRate().basisPointChange5())
                .isEqualByComparingTo("18.00");
        assertThat(snapshot.realRate().basisPointChange20())
                .isEqualByComparingTo("28.00");
        assertThat(snapshot.realRate().collectedAt())
                .isEqualTo(RATE_COLLECTED_AT);
        assertThat(snapshot.realRateAssessment().status())
                .isEqualTo(GoldFactorStatus.PRESSURING);
        assertThat(snapshot.dollarIndex().currentIndex())
                .isEqualByComparingTo("120.00");
        assertThat(snapshot.dollarIndex().return1())
                .isEqualByComparingTo("2.5641");
        assertThat(snapshot.dollarIndex().return5())
                .isEqualByComparingTo("2.5641");
        assertThat(snapshot.dollarIndex().return20())
                .isEqualByComparingTo("2.5641");
        assertThat(snapshot.dollarIndexAssessment().status())
                .isEqualTo(GoldFactorStatus.PRESSURING);
        assertThat(snapshot.researchVersion())
                .isEqualTo("gold-multifactor-v2");
        assertThat(snapshot.disclaimer())
                .contains("不构成黄金方向预测或投资建议");

        verify(marketPriceRepository).findRecent("XAUUSD", 120);
        verify(macroObservationRepository).findRecent("DFII10", 120);
        verify(macroObservationRepository).findRecent("DTWEXBGS", 120);
    }

    @Test
    @DisplayName("历史快照只使用截止日期及之前的数据")
    void createsHistoricalSnapshotWithoutFutureData() {
        LocalDate asOf = ANALYSIS_DATE;
        List<MarketPrice> liveGold = goldPrices(21);
        liveGold.add(goldPrice(LocalDate.parse("2026-08-25"), "9999"));
        List<MacroObservation> liveRates = realRates(21);
        liveRates.add(realRate(LocalDate.parse("2026-08-25"), "9.99"));
        List<MacroObservation> liveDollars = dollarIndexes(21);
        liveDollars.add(dollarIndex(LocalDate.parse("2026-08-25"), "999"));

        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(liveGold);
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(liveRates);
        when(macroObservationRepository.findRecent("DTWEXBGS", 120))
                .thenReturn(liveDollars);
        when(marketPriceRepository.findRecent("XAUUSD", asOf, 120))
                .thenReturn(goldPrices(21));
        when(macroObservationRepository.findRecent("DFII10", asOf, 120))
                .thenReturn(realRates(21));
        when(macroObservationRepository.findRecent("DTWEXBGS", asOf, 120))
                .thenReturn(dollarIndexes(21));

        GoldResearchSnapshot snapshot = service.createSnapshot(asOf);

        assertThat(snapshot.latestGoldDate()).isBeforeOrEqualTo(asOf);
        assertThat(snapshot.latestRealRateDate()).isBeforeOrEqualTo(asOf);
        assertThat(snapshot.latestDollarIndexDate()).isBeforeOrEqualTo(asOf);
        assertThat(snapshot.gold().currentPrice())
                .isNotEqualByComparingTo("9999");
        verify(marketPriceRepository).findRecent("XAUUSD", asOf, 120);
        verify(macroObservationRepository).findRecent("DFII10", asOf, 120);
        verify(macroObservationRepository).findRecent("DTWEXBGS", asOf, 120);
    }

    @Test
    @DisplayName("只有 20 个共同日期时明确报告数据不足")
    void rejectsInsufficientCommonDates() {
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(goldPrices(20));
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(20));

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InsufficientResearchDataException.class)
                .hasMessageContaining("实际=20")
                .hasMessageContaining("最低要求=21");
    }

    @Test
    @DisplayName("参与计算的黄金价格为零时拒绝生成结论")
    void rejectsNonPositiveGoldPrice() {
        List<MarketPrice> prices = goldPrices(21);
        prices.set(5, goldPrice(
                ANALYSIS_DATE.minusDays(5),
                "0"
        ));
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(prices);
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InvalidResearchDataException.class)
                .hasMessageContaining("黄金价格必须大于 0");
    }

    @Test
    @DisplayName("任一数据源为空时不生成部分研究快照")
    void rejectsEmptyDataSource() {
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(List.of());
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InsufficientResearchDataException.class)
                .hasMessageContaining("黄金价格");
    }

    @Test
    @DisplayName("没有广义美元指数时拒绝生成部分研究快照")
    void rejectsMissingDollarIndexData() {
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(goldPrices(21));
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));
        when(macroObservationRepository.findRecent("DTWEXBGS", 120))
                .thenReturn(List.of());

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InsufficientResearchDataException.class)
                .hasMessageContaining("美元指数");
    }

    @Test
    @DisplayName("三方共同日期只有20个时报告数据不足")
    void rejectsInsufficientThreeWayCommonDates() {
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(goldPrices(21));
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));
        when(macroObservationRepository.findRecent("DTWEXBGS", 120))
                .thenReturn(dollarIndexes(20));

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InsufficientResearchDataException.class)
                .hasMessageContaining("实际=20")
                .hasMessageContaining("最低要求=21");
    }

    @Test
    @DisplayName("广义美元指数重复日期不会被静默覆盖")
    void rejectsDuplicateDollarIndexDates() {
        List<MacroObservation> indexes = dollarIndexes(21);
        indexes.add(dollarIndex(ANALYSIS_DATE, "119.00"));
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(goldPrices(21));
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));
        when(macroObservationRepository.findRecent("DTWEXBGS", 120))
                .thenReturn(indexes);

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InvalidResearchDataException.class)
                .hasMessageContaining("美元指数")
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("参与计算的广义美元指数必须大于零")
    void rejectsNonPositiveDollarIndex() {
        List<MacroObservation> indexes = dollarIndexes(21);
        indexes.set(5, dollarIndex(
                ANALYSIS_DATE.minusDays(5),
                "0"
        ));
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(goldPrices(21));
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));
        when(macroObservationRepository.findRecent("DTWEXBGS", 120))
                .thenReturn(indexes);

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InvalidResearchDataException.class)
                .hasMessageContaining("美元指数")
                .hasMessageContaining("大于 0");
    }

    @Test
    @DisplayName("重复观测日期不会被静默覆盖")
    void rejectsDuplicateObservationDates() {
        List<MarketPrice> prices = goldPrices(21);
        prices.add(goldPrice(ANALYSIS_DATE, "2199"));
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(prices);
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InvalidResearchDataException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("观测日期为空时返回明确的数据完整性错误")
    void rejectsMissingObservationDate() {
        List<MarketPrice> prices = goldPrices(21);
        prices.add(goldPrice(null, "2200"));
        when(marketPriceRepository.findRecent("XAUUSD", 120))
                .thenReturn(prices);
        when(macroObservationRepository.findRecent("DFII10", 120))
                .thenReturn(realRates(21));

        assertThatThrownBy(service::createSnapshot)
                .isInstanceOf(InvalidResearchDataException.class)
                .hasMessageContaining("黄金观测日期不能为空");
    }

    @Test
    @DisplayName("确定性研究服务不依赖大模型客户端")
    void doesNotDependOnChatClient() {
        assertThat(GoldResearchSnapshotService.class.getDeclaredFields())
                .noneMatch(field ->
                        field.getType().equals(ChatClient.class)
                );
    }

    private List<MarketPrice> goldPrices(int count) {
        List<MarketPrice> prices = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            prices.add(goldPrice(
                    ANALYSIS_DATE.minusDays(index),
                    goldValue(index)
            ));
        }
        return prices;
    }

    private List<MacroObservation> realRates(int count) {
        List<MacroObservation> observations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            observations.add(realRate(
                    ANALYSIS_DATE.minusDays(index),
                    realRateValue(index)
            ));
        }
        return observations;
    }

    private List<MacroObservation> dollarIndexes(int count) {
        List<MacroObservation> observations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            observations.add(dollarIndex(
                    ANALYSIS_DATE.minusDays(index),
                    index == 0 ? "120.00" : "117.00"
            ));
        }
        return observations;
    }

    private String goldValue(int index) {
        return switch (index) {
            case 0 -> "2200";
            case 1 -> "2000";
            case 5 -> "2100";
            case 20 -> "1800";
            default -> "2050";
        };
    }

    private String realRateValue(int index) {
        return switch (index) {
            case 0 -> "2.38";
            case 1 -> "2.30";
            case 5 -> "2.20";
            case 20 -> "2.10";
            default -> "2.25";
        };
    }

    private MarketPrice goldPrice(LocalDate date, String value) {
        return new MarketPrice(
                "XAUUSD",
                date,
                new BigDecimal(value),
                "usd",
                "troy_ounce",
                "alpha_vantage",
                GOLD_COLLECTED_AT
        );
    }

    private MacroObservation realRate(LocalDate date, String value) {
        return new MacroObservation(
                UUID.randomUUID(),
                "DFII10",
                date,
                new BigDecimal(value),
                "percent",
                "fred",
                RATE_COLLECTED_AT,
                null
        );
    }

    private MacroObservation dollarIndex(LocalDate date, String value) {
        return new MacroObservation(
                UUID.randomUUID(),
                "DTWEXBGS",
                date,
                new BigDecimal(value),
                "index_2006_100",
                "fred",
                RATE_COLLECTED_AT,
                null
        );
    }
}
