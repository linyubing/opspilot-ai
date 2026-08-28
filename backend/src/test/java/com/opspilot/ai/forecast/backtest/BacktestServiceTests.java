package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.GoldForecastProperties;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证回测任务的样本选择、参数边界和查询行为。 */
class BacktestServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    private MarketPriceRepository priceRepo;
    private BacktestRepository repo;
    private BacktestService service;

    @BeforeEach
    void setUp() {
        priceRepo = mock(MarketPriceRepository.class);
        repo = mock(BacktestRepository.class);
        service = new BacktestService(
                priceRepo,
                repo,
                new GoldForecastProperties("glm-4.7"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(repo.create(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsTaskFromSettleableDates() {
        when(priceRepo.findRecent("XAUUSD", 81)).thenReturn(prices(81));

        BacktestTask task = service.create(60);

        assertThat(task.sampleCount()).isEqualTo(60);
        assertThat(task.startDate()).isEqualTo(LocalDate.parse("2026-06-21"));
        assertThat(task.endDate()).isEqualTo(LocalDate.parse("2026-08-19"));
        assertThat(task.modelName()).isEqualTo("glm-4.7");
        assertThat(task.promptVersion()).isEqualTo("gold-backtest-prompt-v1");
        assertThat(task.ruleVersion()).isEqualTo(GoldForecastRule.RULE_VERSION);
        assertThat(task.status()).isEqualTo(BacktestStatus.CREATED);
        assertThat(task.createdAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(repo).create(task);
    }

    @Test
    void rejectsInvalidSampleCount() {
        assertThatThrownBy(() -> service.create(0))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("1 到 120");
        assertThatThrownBy(() -> service.create(121))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("1 到 120");
    }

    @Test
    void rejectsInsufficientPrices() {
        when(priceRepo.findRecent("XAUUSD", 81)).thenReturn(prices(80));

        assertThatThrownBy(() -> service.create(60))
                .isInstanceOf(BacktestDataInsufficientException.class)
                .hasMessageContaining("需要=81")
                .hasMessageContaining("实际=80");
    }

    @Test
    void reportsMissingTask() {
        UUID id = UUID.randomUUID();
        when(repo.findTask(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(BacktestNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void validatesResultLimit() {
        assertThatThrownBy(() -> service.results(UUID.randomUUID(), 121))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("1 到 120");
    }

    private List<MarketPrice> prices(int count) {
        List<MarketPrice> result = new ArrayList<>();
        LocalDate newest = LocalDate.parse("2026-08-20");
        for (int index = 0; index < count; index++) {
            result.add(new MarketPrice(
                    "XAUUSD",
                    newest.minusDays(index),
                    new BigDecimal("2500").subtract(BigDecimal.valueOf(index)),
                    "usd",
                    "troy_ounce",
                    "test",
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
            ));
        }
        return result;
    }
}
