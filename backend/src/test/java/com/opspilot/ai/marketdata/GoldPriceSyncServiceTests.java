package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldPriceSyncServiceTests {

    @Test
    @DisplayName("同步时排除周六和周日价格")
    void skipsWeekendPrices() {
        GoldPriceProvider provider = () -> List.of(
                price("2026-08-14"), // 周五
                price("2026-08-15"), // 周六
                price("2026-08-16"), // 周日
                price("2026-08-17")  // 周一
        );
        RecordingRepository repository = new RecordingRepository();
        GoldPriceSyncService service =
                new GoldPriceSyncService(provider, repository);

        GoldPriceSyncResult result = service.syncDailyPrices();

        assertThat(repository.savedPrices())
                .extracting(MarketPrice::priceDate)
                .containsExactly(
                        LocalDate.of(2026, 8, 14),
                        LocalDate.of(2026, 8, 17)
                );
        assertThat(result.receivedCount()).isEqualTo(4);
        assertThat(result.savedCount()).isEqualTo(2);
        assertThat(result.weekendSkippedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("供应商返回空数据时拒绝同步")
    void rejectsEmptyResponse() {
        GoldPriceProvider provider = List::of;
        RecordingRepository repository = new RecordingRepository();
        GoldPriceSyncService service =
                new GoldPriceSyncService(provider, repository);

        assertThatThrownBy(service::syncDailyPrices)
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessage("黄金历史价格为空");
        assertThat(repository.savedPrices()).isEmpty();
    }

    @Test
    @DisplayName("同步前按照价格日期升序排列")
    void sortsPricesBeforeSaving() {
        GoldPriceProvider provider = () -> List.of(
                price("2026-08-17"),
                price("2026-08-14")
        );
        RecordingRepository repository = new RecordingRepository();
        GoldPriceSyncService service =
                new GoldPriceSyncService(provider, repository);

        GoldPriceSyncResult result = service.syncDailyPrices();

        assertThat(repository.savedPrices())
                .extracting(MarketPrice::priceDate)
                .containsExactly(
                        LocalDate.of(2026, 8, 14),
                        LocalDate.of(2026, 8, 17)
                );
        assertThat(result.latestPriceDate())
                .isEqualTo(LocalDate.of(2026, 8, 17));
    }

    @Test
    @DisplayName("供应商只返回周末数据时拒绝同步")
    void rejectsWeekendOnlyResponse() {
        GoldPriceProvider provider = () -> List.of(
                price("2026-08-15"),
                price("2026-08-16")
        );
        GoldPriceSyncService service = new GoldPriceSyncService(
                provider,
                new RecordingRepository()
        );

        assertThatThrownBy(service::syncDailyPrices)
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessage("黄金历史价格没有有效工作日数据");
    }

    /**
     * 固定价格只用于构造日期过滤输入，不代表真实黄金行情。
     */
    private MarketPrice price(String date) {
        return new MarketPrice(
                "XAUUSD",
                LocalDate.parse(date),
                new BigDecimal("100.00000000"),
                "usd",
                "troy_ounce",
                "sync_rule_test",
                OffsetDateTime.of(
                        2026,
                        8,
                        17,
                        8,
                        0,
                        0,
                        0,
                        ZoneOffset.UTC
                )
        );
    }

    /**
     * 只记录同步服务交给 Repository 的数据，
     * 避免单元测试连接真实数据库。
     */
    private static final class RecordingRepository
            implements MarketPriceRepository {

        private List<MarketPrice> savedPrices = List.of();

        @Override
        public void saveAll(List<MarketPrice> prices) {
            savedPrices = List.copyOf(prices);
        }

        @Override
        public Optional<MarketPrice> findLatest(String symbol) {
            return Optional.empty();
        }

        @Override
        public List<MarketPrice> findRecent(String symbol, int limit) {
            return List.of();
        }

        @Override
        public List<MarketPrice> findAll(String symbol) {
            return savedPrices.stream()
                    .filter(price -> price.symbol().equals(symbol))
                    .sorted(java.util.Comparator.comparing(
                            MarketPrice::priceDate
                    ))
                    .toList();
        }

        @Override
        public List<MarketPrice> findAfter(
                String symbol,
                LocalDate baseDate,
                int limit
        ) {
            throw new AssertionError("同步服务测试不应查询后续价格");
        }

        List<MarketPrice> savedPrices() {
            return savedPrices;
        }
    }
}
