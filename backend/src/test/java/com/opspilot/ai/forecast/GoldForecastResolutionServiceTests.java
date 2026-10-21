package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import com.opspilot.ai.marketdata.GoldSessionCheckService;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
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
    @Mock
    private GoldSessionCheckService sessionCheck;

    private GoldForecastResolutionService service;

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    @DisplayName("目标窗口结束前等待，恰好结束及结束后才查询目标日线")
    void waitsBeforeLookup(int seconds) {
        var forecast = timedForecast("2026-08-31");
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        service = new GoldForecastResolutionService(forecastRepository, goldRepository, new GoldForecastRule(),
                Clock.fixed(forecast.timing().end().plusSeconds(seconds), ZoneOffset.UTC), sessionCheck);
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        var receipt = ArgumentCaptor.forClass(GoldResolutionCheck.class);
        verify(forecastRepository).recordCheck(receipt.capture());
        assertThat(receipt.getValue().code()).isEqualTo(seconds < 0
                ? GoldResolutionCheck.Code.WAITING_END : GoldResolutionCheck.Code.WAITING_BAR);
        if (seconds < 0) verifyNoInteractions(goldRepository);
        else verify(goldRepository).findNext("XAUUSD", "twelve_data", FRIDAY);
        verifyNoInteractions(sessionCheck);
        verify(forecastRepository, never()).resolve(any(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"original", "missing", "late"})
    @DisplayName("正式结算只核验预测前冻结的完整基准，不能用同收盘价的新版OHLC替代")
    void usesFrozenBase(String issue) {
        var old = timedForecast("2026-08-31");
        var forecast = new StoredGoldDirectionForecast(old.id(), old.snapshotId(), old.baseDate(), old.basePrice(),
                old.predictedDirection(), old.reasoning(), old.invalidationConditions(), old.modelName(),
                GoldForecastPromptBuilder.FORMAL_VERSION, old.promptHash(), old.forecastRuleVersion(),
                old.rawResponse(), old.status(), null, null, null, null, null, null, old.createdAt(), old.timing());
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        var target = bar("2026-08-31", "2525");
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY)).thenReturn(Optional.of(target));
        var revised = bar("2026-08-28", "2500");
        org.mockito.Mockito.lenient().when(goldRepository.findRecent("XAUUSD", "twelve_data", FRIDAY, 1))
                .thenReturn(List.of(revised));
        var snapshots = org.mockito.Mockito.mock(com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository.class);
        var savedAt = issue.equals("late") ? forecast.createdAt().plusSeconds(1) : forecast.createdAt().minusHours(1);
        var value = com.opspilot.ai.analysis.GoldSnapshotFixtures.withInput(
                GoldForecastTestFixtures.snapshot("2500").snapshot(), FRIDAY,
                "gold-multifactor-contiguous-v4", savedAt);
        var frozen = value.input().bars().getFirst();
        org.mockito.Mockito.lenient().when(snapshots.findById(forecast.snapshotId())).thenReturn(issue.equals("missing")
                ? Optional.empty() : Optional.of(new com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot(
                        forecast.snapshotId(), value, savedAt)));
        org.mockito.Mockito.lenient().when(sessionCheck.check(any(GoldDailyBar.class)))
                .thenAnswer(call -> proof(call.getArgument(0), "MATCHED"));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(call -> resolvedForecast(forecast, call.getArgument(1)));
        service = new GoldForecastResolutionService(forecastRepository, goldRepository, new GoldForecastRule(),
                Clock.fixed(NOW, ZoneOffset.UTC), sessionCheck, snapshots);
        assertThat(service.resolvePending(10).resolvedCount()).isEqualTo(issue.equals("original") ? 1 : 0);
        if (issue.equals("original")) {
            verify(sessionCheck).check(frozen);
            verify(sessionCheck, never()).check(revised);
        } else {
            verifyNoInteractions(sessionCheck);
            verify(forecastRepository, never()).resolve(any(), any());
        }
    }

    @ParameterizedTest
    @CsvSource({"base,missing", "target,missing", "base,price", "target,price",
            "base,hours", "target,hours", "base,timezone", "target,timezone"})
    @DisplayName("结算不能仅相信MATCHED标志，必须重放绑定原价的完整小时依据")
    void rejectsUnboundProof(String side, String issue) {
        prepareSession();
        var base = bar("2026-08-28", "2500");
        var target = bar("2026-08-31", "2525");
        var day = side.equals("base") ? base : target;
        var correct = proof(day, "MATCHED");
        var input = correct.input();
        var badInput = switch (issue) {
            case "missing" -> null;
            case "price" -> new GoldSessionCheckResult.Input(bar(day.priceDate().toString(), "2499"),
                    input.hours(), input.timezoneBasis());
            case "hours" -> new GoldSessionCheckResult.Input(day, input.hours().subList(0, 23), input.timezoneBasis());
            default -> new GoldSessionCheckResult.Input(day, input.hours(), "UNKNOWN");
        };
        var bad = new GoldSessionCheckResult(correct.session(), correct.checkedAt(),
                GoldSessionCheckResult.Status.MATCHED, 24, 24, List.of(), "不可信标志", badInput);
        when(sessionCheck.check(base)).thenReturn(side.equals("base") ? bad : proof(base, "MATCHED"));
        if (side.equals("target")) when(sessionCheck.check(target)).thenReturn(bad);
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
        var receipt = ArgumentCaptor.forClass(GoldResolutionCheck.class);
        verify(forecastRepository).recordCheck(receipt.capture());
        assertThat(receipt.getValue().code()).isEqualTo(side.equals("base")
                ? GoldResolutionCheck.Code.BASE_SESSION_FAILED : GoldResolutionCheck.Code.TARGET_SESSION_FAILED);
    }

    @Test @DisplayName("新时段合同不能仅凭同日期的闭市标志结算")
    void waitsForSessionProof() {
        var forecast = timedForecast("2026-08-31");
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-08-31", "2525")));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));

        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"NOT_ENDED", "MISSING_HOURS", "INVALID_INPUT", "OHLC_MISMATCH", "unavailable"})
    @DisplayName("目标小时核验未通过或来源不可用时保持待验证")
    void rejectsBadTargetSession(String status) {
        var forecast = prepareSession();
        var base = bar("2026-08-28", "2500");
        var target = bar("2026-08-31", "2525");
        when(sessionCheck.check(base)).thenReturn(proof(base, "MATCHED"));
        if (status.equals("unavailable")) {
            when(sessionCheck.check(target)).thenThrow(new MarketDataUnavailableException("来源暂不可用"));
        } else {
            when(sessionCheck.check(target)).thenReturn(proof(target, status));
        }
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
        verify(forecastRepository).markScanned(forecast.id());
    }

    @Test @DisplayName("基准小时聚合不匹配，不能仅核验目标就评分")
    void rejectsBadBaseSession() {
        prepareSession();
        var base = bar("2026-08-28", "2500");
        when(sessionCheck.check(base)).thenReturn(proof(base, "OHLC_MISMATCH"));
        assertThat(service.resolvePending(10).resolvedCount()).isZero();
        verify(forecastRepository, never()).resolve(any(), any());
        verify(sessionCheck, never()).check(bar("2026-08-31", "2525"));
    }

    @Test @DisplayName("两端原价均匹配承诺小时窗口才允许结算")
    void resolvesCheckedSessions() {
        var forecast = prepareSession();
        var base = bar("2026-08-28", "2500");
        var target = bar("2026-08-31", "2525");
        when(sessionCheck.check(base)).thenReturn(proof(base, "MATCHED"));
        when(sessionCheck.check(target)).thenReturn(proof(target, "MATCHED"));
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 1, 0));
        var saved = ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), saved.capture());
        assertThat(saved.getValue().actualReturn()).isEqualByComparingTo("1");
        assertThat(saved.getValue().hit()).isTrue();
    }

    @Test @DisplayName("仓库基准已换价时不能替换预测冻结价")
    void rejectsRevisedBase() {
        prepareSession();
        when(goldRepository.findRecent("XAUUSD", "twelve_data", FRIDAY, 1))
                .thenReturn(List.of(bar("2026-08-28", "2501")));
        assertThat(service.resolvePending(10).resolvedCount()).isZero();
        verifyNoInteractions(sessionCheck);
        verify(forecastRepository, never()).resolve(any(), any());
    }

    @Test @DisplayName("匹配结果属于另一标签日时不能结算")
    void rejectsWrongProofDate() {
        prepareSession();
        when(sessionCheck.check(bar("2026-08-28", "2500")))
                .thenReturn(proof(bar("2026-08-27", "2500"), "MATCHED"));
        assertThat(service.resolvePending(10).resolvedCount()).isZero();
        verify(forecastRepository, never()).resolve(any(), any());
    }

    @Test @DisplayName("核验后的结算时间不得早于来源核验时刻")
    void rejectsFutureProof() {
        prepareSession();
        var base = bar("2026-08-28", "2500");
        var target = bar("2026-08-31", "2525");
        when(sessionCheck.check(base)).thenReturn(proof(base, "MATCHED"));
        when(sessionCheck.check(target)).thenReturn(new GoldSessionCheckResult(
                GoldSession.forDate(target.priceDate()), NOW.plusSeconds(1),
                GoldSessionCheckResult.Status.MATCHED, 24, 24, List.of(), "数学样例",
                proof(target, "MATCHED").input()));
        assertThat(service.resolvePending(10).resolvedCount()).isZero();
        verify(forecastRepository, never()).resolve(any(), any());
    }

    @Test @DisplayName("正式新版本缺少时段时不得降级为旧日期结算")
    void rejectsMissingFormalTiming() {
        var old = pendingForecast("2500", ForecastDirection.BULLISH);
        var forecast = new StoredGoldDirectionForecast(old.id(), old.snapshotId(), old.baseDate(), old.basePrice(),
                old.predictedDirection(), old.reasoning(), old.invalidationConditions(), old.modelName(),
                GoldForecastPromptBuilder.FORMAL_VERSION, old.promptHash(), old.forecastRuleVersion(),
                old.rawResponse(), old.status(), null, null, null, null, null, null, old.createdAt());
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-08-31", "2525")));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));
        assertThat(service.resolvePending(10).resolvedCount()).isZero();
        verify(forecastRepository, never()).resolve(any(), any());
        verifyNoInteractions(sessionCheck);
    }

    @Test @DisplayName("来源故障只保留当前记录，后续旧协议记录仍可扫描")
    void continuesAfterSourceFailure() {
        var timed = prepareSession();
        var legacy = pendingForecast("2500", ForecastDirection.BULLISH);
        when(forecastRepository.findPending(10)).thenReturn(List.of(timed, legacy));
        when(sessionCheck.check(bar("2026-08-28", "2500")))
                .thenThrow(new MarketDataUnavailableException("来源暂不可用"));
        org.mockito.Mockito.doAnswer(invocation -> resolvedForecast(legacy, invocation.getArgument(1)))
                .when(forecastRepository).resolve(eq(legacy.id()), any());
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(2, 1, 1));
        verify(forecastRepository, never()).resolve(eq(timed.id()), any());
        verify(forecastRepository).markScanned(timed.id());
        verify(forecastRepository).markScanned(legacy.id());
    }

    @ParameterizedTest @ValueSource(strings = {"matched", "base", "target"})
    @DisplayName("正式解析通过真实小时核验器绑定两端价格，而非只相信匹配标志")
    void checksHttpPrices(String issue) {
        prepareSession();
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://example.invalid");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var realCheck = new GoldSessionCheckService(builder.build(),
                new com.opspilot.ai.marketdata.TwelveDataProperties(java.net.URI.create("https://example.invalid"),
                        "test-only-key", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)),
                Clock.fixed(NOW, ZoneOffset.UTC), new com.opspilot.ai.marketdata.GoldSessionCheck());
        service = new GoldForecastResolutionService(forecastRepository, goldRepository, new GoldForecastRule(),
                Clock.fixed(NOW, ZoneOffset.UTC), realCheck);
        expectHours(server, "2026-08-27T21:00:00", "2026-08-28T21:00:00", issue.equals("base") ? "2501" : "2500");
        if (!issue.equals("base")) {
            expectHours(server, "2026-08-30T21:00:00", "2026-08-31T21:00:00", issue.equals("target") ? "2526" : "2525");
        }
        assertThat(service.resolvePending(10)).isEqualTo(issue.equals("matched")
                ? new ResolveGoldForecastsResult(1, 1, 0) : new ResolveGoldForecastsResult(1, 0, 1));
        if (!issue.equals("matched")) verify(forecastRepository, never()).resolve(any(), any());
        var check = ArgumentCaptor.forClass(GoldResolutionCheck.class);
        verify(forecastRepository).recordCheck(check.capture());
        assertThat(check.getValue().code()).isEqualTo(switch (issue) {
            case "base" -> GoldResolutionCheck.Code.BASE_SESSION_FAILED;
            case "target" -> GoldResolutionCheck.Code.TARGET_SESSION_FAILED;
            default -> GoldResolutionCheck.Code.CHECK_PASSED;
        });
        assertThat(check.getValue().baseCheck().input().hours()).hasSize(24);
        assertThat(check.getValue().baseCheck().input().day().close()).isEqualByComparingTo("2500");
        if (!issue.equals("base")) {
            assertThat(check.getValue().targetCheck().input().day().close()).isEqualByComparingTo("2525");
        }
        server.verify();
    }

    private void expectHours(org.springframework.test.web.client.MockRestServiceServer server,
            String start, String end, String price) {
        // 仅为离线数学测试构造整齐小时线，不进入行情仓库或模型实验。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode().put("status", "ok");
        root.putObject("meta").put("symbol", "XAU/USD").put("interval", "1h").put("timezone", "UTC");
        var rows = root.putArray("values");
        var value = new BigDecimal(price);
        var first = java.time.LocalDateTime.parse(start);
        for (int i = 0; i < 24; i++) {
            rows.addObject().put("datetime", first.plusHours(i).toString().replace('T', ' ') + ":00")
                    .put("open", price).put("high", value.add(BigDecimal.TEN).toPlainString())
                    .put("low", value.subtract(BigDecimal.TEN).toPlainString()).put("close", price);
        }
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("interval", "1h"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("timezone", "UTC"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("start_date", start))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("end_date", end))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        root.toString(), org.springframework.http.MediaType.APPLICATION_JSON));
    }

    private StoredGoldDirectionForecast prepareSession() {
        var forecast = timedForecast("2026-08-31");
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-08-31", "2525")));
        when(goldRepository.findRecent("XAUUSD", "twelve_data", FRIDAY, 1))
                .thenReturn(List.of(bar("2026-08-28", "2500")));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));
        return forecast;
    }

    private GoldSessionCheckResult proof(GoldDailyBar bar, String status) {
        var session = GoldSession.forDate(bar.priceDate());
        // 仅用于离线算法边界测试，不写入真实行情或成绩。
        var hours = java.util.stream.LongStream.range(0, 24).mapToObj(i ->
                new com.opspilot.ai.marketdata.GoldHourBar(session.start().plusSeconds(i * 3600),
                        bar.open(), bar.high(), bar.low(), bar.close())).toList();
        return new GoldSessionCheckResult(GoldSession.forDate(bar.priceDate()), NOW,
                GoldSessionCheckResult.Status.valueOf(status), 24, 24, List.of(), "数学样例",
                new GoldSessionCheckResult.Input(bar, hours, "REQUEST_ONLY_UTC"));
    }

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
                Clock.fixed(Instant.parse("2026-08-31T15:00:00Z"), ZoneOffset.UTC), sessionCheck);
        var forecast = timedForecast("2026-08-31");
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        org.mockito.Mockito.lenient().when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-08-31", "2525", "2026-08-31T12:00:00Z")));
        org.mockito.Mockito.lenient().when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 0, 1));
        verify(forecastRepository, never()).resolve(any(), any());
        verifyNoInteractions(goldRepository, sessionCheck);
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
                Clock.fixed(NOW, ZoneOffset.UTC), sessionCheck
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
                Clock.fixed(Instant.parse("2026-09-02T14:00:00Z"), ZoneOffset.UTC), sessionCheck);
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
                forecastRepository, goldRepository, new GoldForecastRule(), changing, sessionCheck);
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

    @Test
    @DisplayName("弃权仍保存真实结算结果但命中字段为空")
    void resolvesAbstentionWithoutInventingDirectionHit() {
        var source = pendingForecast("2500", null);
        var forecast = new StoredGoldDirectionForecast(source.id(), source.snapshotId(), source.baseDate(),
                source.basePrice(), null, source.reasoning(), source.invalidationConditions(), source.modelName(),
                source.promptVersion(), source.promptHash(), source.forecastRuleVersion(), source.rawResponse(),
                source.status(), null, null, null, null, null, null, source.createdAt(), null, ForecastSignal.ABSTAINED);
        when(forecastRepository.findPending(10)).thenReturn(List.of(forecast));
        when(goldRepository.findNext("XAUUSD", "twelve_data", FRIDAY))
                .thenReturn(Optional.of(bar("2026-08-31", "2525")));
        when(forecastRepository.resolve(any(), any()))
                .thenAnswer(invocation -> resolvedForecast(forecast, invocation.getArgument(1)));
        assertThat(service.resolvePending(10)).isEqualTo(new ResolveGoldForecastsResult(1, 1, 0));
        var saved = ArgumentCaptor.forClass(ForecastResolution.class);
        verify(forecastRepository).resolve(eq(forecast.id()), saved.capture());
        assertThat(saved.getValue().targetDate()).isEqualTo("2026-08-31");
        assertThat(saved.getValue().actualReturn()).isEqualByComparingTo("1");
        assertThat(saved.getValue().actualDirection()).isEqualTo(ForecastDirection.BULLISH);
        assertThat(saved.getValue().hit()).isNull();
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
