package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldForecastResolutionServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-31T01:00:00Z");
    private static final LocalDate FRIDAY = LocalDate.parse("2026-08-28");

    @Mock
    private GoldForecastRepository forecastRepository;
    @Mock
    private GoldDailyBarRepository goldRepository;

    private GoldForecastResolutionService service;

    @BeforeEach
    void setUp() {
        service = new GoldForecastResolutionService(
                forecastRepository,
                goldRepository,
                new GoldForecastRule(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsLimitOutsideOneToOneHundred(int limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolvePending(limit))
                .withMessage("limit 必须在 1 到 100 之间");

        verifyNoInteractions(forecastRepository, goldRepository);
    }

    @Test
    void returnsZeroCountsWhenThereAreNoPendingForecasts() {
        when(forecastRepository.findPending(20)).thenReturn(List.of());

        ResolveGoldForecastsResult result = service.resolvePending(20);

        assertThat(result).isEqualTo(new ResolveGoldForecastsResult(0, 0, 0));
        verifyNoInteractions(goldRepository);
    }

    @Test
    void resolvesFridayForecastUsingMondayRealPrice() {
        StoredGoldDirectionForecast forecast = pendingForecast(
                "2500.000000", ForecastDirection.BULLISH
        );
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext(
                "XAUUSD", "twelve_data", FRIDAY
        )).thenReturn(Optional.of(
                bar("2026-08-31", "2525.000000")
        ));
        when(forecastRepository.resolve(any(), any())).thenAnswer(invocation ->
                resolvedForecast(forecast, invocation.getArgument(1))
        );

        ResolveGoldForecastsResult result = service.resolvePending(10);

        assertThat(result).isEqualTo(new ResolveGoldForecastsResult(1, 1, 0));
        ArgumentCaptor<ForecastResolution> captor =
                ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), captor.capture());
        ForecastResolution resolution = captor.getValue();
        assertThat(resolution.targetDate()).isEqualTo("2026-08-31");
        assertThat(resolution.targetPrice()).isEqualByComparingTo("2525.000000");
        assertThat(resolution.actualReturn()).isEqualByComparingTo("1.000000");
        assertThat(resolution.actualDirection()).isEqualTo(ForecastDirection.BULLISH);
        assertThat(resolution.hit()).isTrue();
        assertThat(resolution.resolvedAt()).isEqualTo(
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void keepsForecastPendingWithoutNextRealBar() {
        StoredGoldDirectionForecast forecast = pendingForecast(
                "2500.000000", ForecastDirection.NEUTRAL
        );
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext(
                "XAUUSD", "twelve_data", FRIDAY
        )).thenReturn(Optional.empty());

        ResolveGoldForecastsResult result = service.resolvePending(10);

        assertThat(result).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
    }

    @Test
    void selectsNextRecordedWeekdayWhenHolidayHasNoPrice() {
        StoredGoldDirectionForecast forecast = pendingForecast(
                "2500.000000", ForecastDirection.BEARISH
        );
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        // 周一节假日没有记录，候选数据直接从周二开始。
        when(goldRepository.findNext(
                "XAUUSD", "twelve_data", FRIDAY
        )).thenReturn(Optional.of(
                bar("2026-09-01", "2475.000000")
        ));
        when(forecastRepository.resolve(any(), any())).thenAnswer(invocation ->
                resolvedForecast(forecast, invocation.getArgument(1))
        );

        service.resolvePending(10);

        ArgumentCaptor<ForecastResolution> captor =
                ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), captor.capture());
        assertThat(captor.getValue().targetDate()).isEqualTo("2026-09-01");
        assertThat(captor.getValue().actualReturn()).isEqualByComparingTo("-1.000000");
        assertThat(captor.getValue().actualDirection()).isEqualTo(ForecastDirection.BEARISH);
        assertThat(captor.getValue().hit()).isTrue();
    }

    @Test
    void roundsActualReturnToSixDecimalPlaces() {
        StoredGoldDirectionForecast forecast = pendingForecast(
                "3000.000000", ForecastDirection.NEUTRAL
        );
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext(
                "XAUUSD", "twelve_data", FRIDAY
        )).thenReturn(Optional.of(
                bar("2026-08-31", "3010.000000")
        ));
        when(forecastRepository.resolve(any(), any())).thenAnswer(invocation ->
                resolvedForecast(forecast, invocation.getArgument(1))
        );

        service.resolvePending(10);

        ArgumentCaptor<ForecastResolution> captor =
                ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), captor.capture());
        assertThat(captor.getValue().actualReturn()).isEqualByComparingTo("0.333333");
    }

    @Test
    void reusesNeutralBoundaryRuleAndMarksWrongPredictionAsMiss() {
        StoredGoldDirectionForecast forecast = pendingForecast(
                "2500.000000", ForecastDirection.BULLISH
        );
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext(
                "XAUUSD", "twelve_data", FRIDAY
        )).thenReturn(Optional.of(
                bar("2026-08-31", "2512.500000")
        ));
        when(forecastRepository.resolve(any(), any())).thenAnswer(invocation ->
                resolvedForecast(forecast, invocation.getArgument(1))
        );

        service.resolvePending(10);

        ArgumentCaptor<ForecastResolution> captor =
                ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), captor.capture());
        assertThat(captor.getValue().actualReturn()).isEqualByComparingTo("0.500000");
        assertThat(captor.getValue().actualDirection()).isEqualTo(ForecastDirection.NEUTRAL);
        assertThat(captor.getValue().hit()).isFalse();
    }

    private StoredGoldDirectionForecast pendingForecast(
            String basePrice,
            ForecastDirection predictedDirection
    ) {
        return new StoredGoldDirectionForecast(
                UUID.randomUUID(), GoldForecastTestFixtures.SNAPSHOT_ID,
                FRIDAY, new BigDecimal(basePrice), predictedDirection,
                "固定测试依据", List.of("固定测试失效条件"), "glm-4.7",
                GoldForecastPromptBuilder.PROMPT_VERSION, "a".repeat(64),
                GoldForecastRule.RULE_VERSION, "固定测试响应", ForecastStatus.PENDING,
                null, null, null, null, null, null,
                OffsetDateTime.parse("2026-08-28T01:00:00Z")
        );
    }

    private GoldDailyBar bar(String date, String close) {
        BigDecimal value = new BigDecimal(close);
        return new GoldDailyBar(
                "XAUUSD", LocalDate.parse(date),
                value, value.add(BigDecimal.TEN),
                value.subtract(BigDecimal.TEN), value,
                "usd", "troy_ounce", "twelve_data",
                OffsetDateTime.parse(date + "T23:00:00Z")
        );
    }

    private StoredGoldDirectionForecast resolvedForecast(
            StoredGoldDirectionForecast source,
            ForecastResolution resolution
    ) {
        return new StoredGoldDirectionForecast(
                source.id(), source.snapshotId(), source.baseDate(), source.basePrice(),
                source.predictedDirection(), source.reasoning(),
                source.invalidationConditions(), source.modelName(),
                source.promptVersion(), source.promptHash(),
                source.forecastRuleVersion(), source.rawResponse(),
                ForecastStatus.RESOLVED, resolution.targetDate(),
                resolution.targetPrice(), resolution.actualReturn(),
                resolution.actualDirection(), resolution.hit(),
                resolution.resolvedAt(), source.createdAt()
        );
    }
}
