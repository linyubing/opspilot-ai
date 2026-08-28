package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.GoldForecastProperties;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证回测任务的样本选择、参数边界和查询行为。 */
class BacktestServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    private MarketPriceRepository priceRepo;
    private BacktestRepository repo;
    private BacktestDateSelector selector;
    private BacktestService service;

    @BeforeEach
    void setUp() {
        priceRepo = mock(MarketPriceRepository.class);
        repo = mock(BacktestRepository.class);
        selector = mock(BacktestDateSelector.class);
        service = new BacktestService(
                priceRepo,
                repo,
                selector,
                new GoldForecastProperties("glm-4.7"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(repo.create(any(), anyList()))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsTaskFromSettleableDates() {
        List<MarketPrice> history = prices(101);
        List<LocalDate> selected = List.of(
                LocalDate.parse("2012-01-03"),
                LocalDate.parse("2020-06-15"),
                LocalDate.parse("2026-08-19")
        );
        when(priceRepo.findAll("XAUUSD")).thenReturn(history);
        when(selector.select(history, 3)).thenReturn(selected);

        BacktestTask task = service.create(3);

        assertThat(task.sampleCount()).isEqualTo(3);
        assertThat(task.startDate()).isEqualTo(LocalDate.parse("2012-01-03"));
        assertThat(task.endDate()).isEqualTo(LocalDate.parse("2026-08-19"));
        assertThat(task.modelName()).isEqualTo("glm-4.7");
        assertThat(task.promptVersion()).isEqualTo("gold-backtest-prompt-v1");
        assertThat(task.ruleVersion()).isEqualTo(GoldForecastRule.RULE_VERSION);
        assertThat(task.status()).isEqualTo(BacktestStatus.CREATED);
        assertThat(task.createdAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        ArgumentCaptor<BacktestTask> taskCaptor =
                ArgumentCaptor.forClass(BacktestTask.class);
        verify(repo).create(taskCaptor.capture(), eq(selected));
        assertThat(taskCaptor.getValue().startDate()).isEqualTo(task.startDate());
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
        List<MarketPrice> history = prices(80);
        when(priceRepo.findAll("XAUUSD")).thenReturn(history);
        when(selector.select(history, 60)).thenThrow(
                new BacktestDataInsufficientException(
                        "黄金有效交易日期不足，需要=81，实际=80"
                )
        );

        assertThatThrownBy(() -> service.create(60))
                .isInstanceOf(BacktestDataInsufficientException.class)
                .hasMessageContaining("需要=81")
                .hasMessageContaining("实际=80");
    }

    @Test
    void returnsFrozenSampleDates() {
        UUID id = UUID.randomUUID();
        List<LocalDate> dates = List.of(
                LocalDate.parse("2012-01-03"),
                LocalDate.parse("2026-08-19")
        );
        when(repo.findTask(id)).thenReturn(Optional.of(task(id)));
        when(repo.findSampleDates(id)).thenReturn(dates);

        assertThat(service.samples(id)).containsExactlyElementsOf(dates);
    }

    @Test
    void doesNotReadSamplesWhenTaskIsMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findTask(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.samples(id))
                .isInstanceOf(BacktestNotFoundException.class);
        verify(repo, never()).findSampleDates(id);
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

    private BacktestTask task(UUID id) {
        return new BacktestTask(
                id,
                LocalDate.parse("2012-01-03"),
                LocalDate.parse("2026-08-19"),
                2,
                "glm-4.7",
                "gold-backtest-prompt-v1",
                GoldForecastRule.RULE_VERSION,
                BacktestStatus.CREATED,
                0, 0, 0, null,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                null, null
        );
    }
}
