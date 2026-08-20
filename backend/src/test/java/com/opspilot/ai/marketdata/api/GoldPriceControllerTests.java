package com.opspilot.ai.marketdata.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opspilot.ai.marketdata.GoldPriceProvider;
import com.opspilot.ai.marketdata.GoldPriceSyncService;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoldPriceControllerTests {

    private MockMvc mockMvc;
    private InMemoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        GoldPriceProvider provider = () -> List.of(
                price("2026-08-14", "3333.25"),
                price("2026-08-15", "3340.10")
        );
        GoldPriceSyncService syncService =
                new GoldPriceSyncService(provider, repository);

        GoldPriceController controller =
                new GoldPriceController(syncService, repository);

        /*
         * standaloneSetup 不会加载 Spring Boot 的 JSON 自动配置，
         * 因此测试中需要显式注册 Java 时间类型，确保日期输出为 yyyy-MM-dd。
         */
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(objectMapper)
                )
                .build();
    }

    @Test
    @DisplayName("手动同步后返回接收、保存和周末过滤数量")
    void syncsDailyPrices() throws Exception {
        mockMvc.perform(post("/api/market-data/gold/daily/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(2))
                .andExpect(jsonPath("$.savedCount").value(1))
                .andExpect(jsonPath("$.weekendSkippedCount").value(1))
                .andExpect(jsonPath("$.latestPriceDate")
                        .value("2026-08-14"));
    }

    @Test
    @DisplayName("查询最近一条黄金参考价")
    void returnsLatestPrice() throws Exception {
        mockMvc.perform(post("/api/market-data/gold/daily/sync"));

        mockMvc.perform(get("/api/market-data/gold/daily/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("XAUUSD"))
                .andExpect(jsonPath("$.priceDate").value("2026-08-14"))
                .andExpect(jsonPath("$.referencePrice").value(3333.25))
                .andExpect(jsonPath("$.currency").value("usd"))
                .andExpect(jsonPath("$.unit").value("troy_ounce"));
    }

    @Test
    @DisplayName("按 limit 查询最近黄金参考价")
    void returnsRecentPrices() throws Exception {
        mockMvc.perform(post("/api/market-data/gold/daily/sync"));

        mockMvc.perform(get("/api/market-data/gold/daily")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].priceDate")
                        .value("2026-08-14"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 501})
    @DisplayName("limit 超出 1 到 500 时返回 400")
    void rejectsInvalidLimit(int limit) throws Exception {
        mockMvc.perform(get("/api/market-data/gold/daily")
                        .param("limit", String.valueOf(limit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_MARKET_DATA_REQUEST"));
    }

    @Test
    @DisplayName("limit 等于 500 时允许查询")
    void acceptsMaximumLimit() throws Exception {
        mockMvc.perform(get("/api/market-data/gold/daily")
                        .param("limit", "500"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未传 limit 时默认查询 60 条")
    void usesDefaultLimit() throws Exception {
        mockMvc.perform(get("/api/market-data/gold/daily"))
                .andExpect(status().isOk());

        assertThat(repository.lastLimit).isEqualTo(60);
    }

    @Test
    @DisplayName("没有黄金价格时最新价接口返回 404")
    void returns404WhenLatestPriceDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/market-data/gold/daily/latest"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("行情供应商不可用时返回 503")
    void returns503WhenProviderUnavailable() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        GoldPriceProvider failingProvider = () -> {
            throw new MarketDataUnavailableException(
                    "黄金行情服务暂时不可用"
            );
        };
        GoldPriceController controller = new GoldPriceController(
                new GoldPriceSyncService(failingProvider, repository),
                repository
        );

        MockMvc failingMockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        failingMockMvc.perform(
                        post("/api/market-data/gold/daily/sync")
                )
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("MARKET_DATA_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("黄金行情服务暂时不可用"));
    }

    private static MarketPrice price(String date, String value) {
        return new MarketPrice(
                "XAUUSD",
                LocalDate.parse(date),
                new BigDecimal(value),
                "usd",
                "troy_ounce",
                "alpha_vantage",
                OffsetDateTime.of(
                        2026, 8, 17, 18, 0, 0, 0,
                        ZoneOffset.ofHours(8)
                )
        );
    }

    /**
     * 这里只替代数据库边界，用来验证 Controller 合同；
     * 固定值不是产品行情，真实行情仍由在线测试和 PostgreSQL 验证。
     */
    private static class InMemoryRepository
            implements MarketPriceRepository {

        private final List<MarketPrice> prices = new ArrayList<>();
        private int lastLimit;

        @Override
        public void saveAll(List<MarketPrice> values) {
            prices.addAll(values);
        }

        @Override
        public Optional<MarketPrice> findLatest(String symbol) {
            return prices.stream()
                    .filter(price -> price.symbol().equals(symbol))
                    .max(Comparator.comparing(MarketPrice::priceDate));
        }

        @Override
        public List<MarketPrice> findRecent(String symbol, int limit) {
            lastLimit = limit;
            return prices.stream()
                    .filter(price -> price.symbol().equals(symbol))
                    .sorted(Comparator.comparing(MarketPrice::priceDate)
                            .reversed())
                    .limit(limit)
                    .toList();
        }
    }
}
