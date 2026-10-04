package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

    // 正向用例必须在目标日线采集之后运行，不能用未来行情证明结算正确。
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final LocalDate FRIDAY = LocalDate.parse("2026-08-28");

    @Mock
    private GoldForecastRepository forecastRepository;
    @Mock
    private GoldDailyBarRepository goldRepository;

    private GoldForecastResolutionService service;

    @Test @DisplayName("真实下一根日线与承诺日不一致时保持等待，不能移动目标")
    void rejectsPromisedDateMismatch() {
        var forecast = timedForecast("2026-09-01");
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-08-31", "2525")));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
    }

    @Test @DisplayName("即使收到确认标志，候选目标时段尚未结束也不能结算")
    void waitsForPromisedEnd() {
        service = new GoldForecastResolutionService(forecastRepository, goldRepository, new GoldForecastRule(),
                Clock.fixed(Instant.parse("2026-08-31T15:00:00Z"), ZoneOffset.UTC));
        var forecast = timedForecast("2026-08-31");
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-08-31", "2525", "2026-08-31T12:00:00Z")));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
    }

    private StoredGoldDirectionForecast timedForecast(String promisedDate) {
        var old = pendingForecast("2500", ForecastDirection.BULLISH);
        var s = com.opspilot.ai.marketdata.GoldSession.forDate(LocalDate.parse(promisedDate));
        return new StoredGoldDirectionForecast(old.id(), old.snapshotId(), old.baseDate(), old.basePrice(),
                old.predictedDirection(), old.reasoning(), old.invalidationConditions(), old.modelName(),
                old.promptVersion(), old.promptHash(), old.forecastRuleVersion(), old.rawResponse(), old.status(),
                old.targetDate(), old.targetPrice(), old.actualReturn(), old.actualDirection(), old.hit(), old.resolvedAt(),
                OffsetDateTime.parse("2026-08-29T12:00:00Z"),
                new GoldForecastTiming(s.date(), s.start(), s.end(), "sydney-0700-candidate-v1"));
    }

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

    @ParameterizedTest
    @CsvSource(value = {
            "2026-09-03,2026-09-02T11:00:00Z",
            "2026-09-01,2026-09-02T12:00:00.000000001Z",
            "2026-09-01,2026-09-02T08:00:00-05:00",
            "2026-09-01,NULL",
            "NULL,2026-09-02T11:00:00Z",
            "2026-08-28,2026-09-01T23:00:00Z",
            "2026-08-27,2026-09-01T23:00:00Z"
    }, nullValues = "NULL")
    @DisplayName("未来或缺失时点、非后续行情不写入结算状态")
    void waitsForValidTime(String date, String collected) {
        StoredGoldDirectionForecast forecast = pendingForecast("2500", ForecastDirection.BULLISH);
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar(date, "2525", collected)));
        // 旧代码会真的产生结算结果；让断言捕获错误状态而不是 mock 空指针。
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));

        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
        verify(goldRepository, never()).findAfter(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("采集时间使用真实瞬间比较，等于结算时刻可以使用")
    void acceptsSameInstant() {
        StoredGoldDirectionForecast forecast = pendingForecast("2500", ForecastDirection.BULLISH);
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-09-01", "2525", "2026-09-02T20:00:00+08:00")));
        when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));

        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 1, 0));
        ArgumentCaptor<ForecastResolution> saved = ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), saved.capture());
        assertThat(saved.getValue().resolvedAt().toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("暂不可见的目标行情继续等待，时间到达后仍结算同一根")
    void resolvesAfterWaiting() {
        StoredGoldDirectionForecast forecast = pendingForecast("2500", ForecastDirection.BULLISH);
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-09-01", "2525", "2026-09-02T13:00:00Z")));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));

        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());

        GoldForecastResolutionService later = new GoldForecastResolutionService(
                forecastRepository, goldRepository, new GoldForecastRule(),
                Clock.fixed(Instant.parse("2026-09-02T14:00:00Z"), ZoneOffset.UTC));
        assertThat(later.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 1, 0));
        ArgumentCaptor<ForecastResolution> saved = ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), saved.capture());
        assertThat(saved.getValue().targetDate()).isEqualTo("2026-09-01");
        assertThat(saved.getValue().resolvedAt()).isEqualTo("2026-09-02T14:00:00Z");
    }

    @Test
    @DisplayName("时钟在调用间跳动时，结算仍沿用校验时刻")
    void freezesTime() {
        AtomicInteger calls = new AtomicInteger();
        Clock changing = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(NOW, zone); }
            @Override public Instant instant() {
                return NOW.plusSeconds(calls.getAndIncrement() * 60L);
            }
        };
        GoldForecastResolutionService changingService = new GoldForecastResolutionService(
                forecastRepository, goldRepository, new GoldForecastRule(), changing);
        StoredGoldDirectionForecast forecast = pendingForecast("2500", ForecastDirection.BULLISH);
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-09-01", "2525", "2026-09-02T12:00:00Z")));
        when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));

        assertThat(changingService.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 1, 0));
        ArgumentCaptor<ForecastResolution> saved = ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), saved.capture());
        assertThat(saved.getValue().resolvedAt().toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("第一目标未确认时等待，不能跳到更晚的已确认行情")
    void waitsForConfirmation() {
        StoredGoldDirectionForecast forecast = pendingForecast("2500", ForecastDirection.BULLISH);
        GoldDailyBar confirmed = bar("2026-08-31", "2525");
        GoldDailyBar raw = new GoldDailyBar(confirmed.symbol(), confirmed.priceDate(),
                confirmed.open(), confirmed.high(), confirmed.low(), confirmed.close(),
                confirmed.currency(), confirmed.unit(), confirmed.provider(), confirmed.collectedAt());
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(raw), Optional.of(confirmed));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));

        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
        verify(goldRepository, never()).findAfter(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());

        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 1, 0));
        ArgumentCaptor<ForecastResolution> saved = ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), saved.capture());
        assertThat(saved.getValue().targetDate()).isEqualTo("2026-08-31");
    }

    @Test
    @DisplayName("采集时间已到但确认时刻尚未到达时不结算")
    void waitsForCheckedTime() {
        StoredGoldDirectionForecast forecast = pendingForecast("2500", ForecastDirection.BULLISH);
        GoldDailyBar base = bar("2026-08-31", "2525");
        GoldDailyBar target = new GoldDailyBar(base.symbol(), base.priceDate(), base.open(),
                base.high(), base.low(), base.close(), base.currency(), base.unit(), base.provider(),
                base.collectedAt(), new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                base.priceDate(), OffsetDateTime.parse("2026-09-02T13:00:00Z"), "a".repeat(64)));
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY)).thenReturn(Optional.of(target));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));

        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
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
        return bar(date, close, date + "T23:00:00Z");
    }

    private GoldDailyBar bar(String date, String close, String collected) {
        BigDecimal value = new BigDecimal(close);
        return new GoldDailyBar(
                "XAUUSD", date == null ? null : LocalDate.parse(date),
                value, value.add(BigDecimal.TEN),
                value.subtract(BigDecimal.TEN), value,
                "usd", "troy_ounce", "twelve_data",
                collected == null ? null : OffsetDateTime.parse(collected),
                // 数学测试样例显式附确认；不是供应商真实回执，也不用于准确率实验。
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                        date == null ? null : LocalDate.parse(date),
                        collected == null ? null : OffsetDateTime.parse(collected), "a".repeat(64))
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
