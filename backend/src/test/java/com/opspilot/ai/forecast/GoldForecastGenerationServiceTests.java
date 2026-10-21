package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldSnapshotFixtures;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.GoldResearchSnapshotNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class GoldForecastGenerationServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final String MODEL_NAME = "glm-4.7";

    @Mock
    private GoldResearchSnapshotRepository snapshotRepository;
    @Mock
    private GoldForecastRepository forecastRepository;
    @Mock
    private GoldForecastPromptBuilder promptBuilder;
    @Mock
    private GoldForecastGateway gateway;
    @Mock
    private GoldForecastValidator validator;
    @Mock
    private com.opspilot.ai.marketdata.GoldDailyBarRepository bars;

    private GoldForecastGenerationService service;

    @Test
    @org.junit.jupiter.api.DisplayName("网关报告其他模型时不能保存到当前模型的正式协议")
    void rejectsWrongGeneratedModel() {
        var snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class)))
                .thenReturn(prompt());
        when(gateway.generate(prompt())).thenReturn(new GeneratedGoldForecast("other-model", "{}", generated().content()));
        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastAiResponseException.class);
        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    void rejectsMissingReportedModel() {
        var snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class)))
                .thenReturn(prompt());
        when(gateway.generate(prompt())).thenReturn(new GeneratedGoldForecast(MODEL_NAME, "{}", generated().content()));
        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastAiResponseException.class);
        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("正式弃权保留状态和空方向，不写成中性")
    void savesExplicitAbstention() {
        var snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class)))
                .thenReturn(prompt());
        var content = new GoldDirectionForecastContent(null, "证据不足", List.of("取得更多真实输入"), ForecastSignal.ABSTAINED);
        when(gateway.generate(prompt())).thenReturn(new GeneratedGoldForecast(MODEL_NAME, "{}", content, MODEL_NAME));
        when(forecastRepository.saveIfAbsent(any())).thenAnswer(call -> new SaveGoldForecastResult(call.getArgument(0), true));
        var result = service.generate(snapshot.id()).record();
        assertThat(result.signal()).isEqualTo(ForecastSignal.ABSTAINED);
        assertThat(result.predictedDirection()).isNull();
        assertThat(result.hit()).isNull();
        assertThat(result.reportedModel()).isEqualTo(MODEL_NAME);
        verify(validator).validateFormal(content);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("正式提示词与保存记录使用同一基准和承诺目标时段")
    void sendsCommittedSessionToModel() {
        var snapshot = confirmed();
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        when(gateway.generate(any())).thenReturn(generated());
        when(forecastRepository.saveIfAbsent(any())).thenAnswer(call ->
                new SaveGoldForecastResult(call.getArgument(0), true));
        var real = new GoldForecastGenerationService(snapshotRepository, forecastRepository,
                new GoldForecastPromptBuilder(), gateway, validator,
                new GoldForecastDataFreshnessPolicy(Clock.fixed(NOW, ZoneOffset.UTC)),
                new GoldForecastProperties(MODEL_NAME), Clock.fixed(NOW, ZoneOffset.UTC), publication());
        var stored = real.generate(snapshot.id()).record();
        var captor = ArgumentCaptor.forClass(GoldForecastPrompt.class);
        verify(gateway).generate(captor.capture());
        var prompt = captor.getValue();
        assertThat(prompt.version()).isEqualTo("gold-direction-session-prompt-v2");
        assertThat(prompt.content()).contains("基准收盘价：2515.75", "基准标签日：2026-08-26",
                "目标标签日：" + stored.timing().targetDate(),
                "目标候选开始（UTC）：" + stored.timing().start(),
                "目标候选结束（UTC）：" + stored.timing().end(),
                "(目标收盘价 / 基准收盘价 - 1) * 100", "不是当前盘中报价",
                "不是官方交易时段证明");
        assertThat(stored.promptVersion()).isEqualTo(prompt.version());
        verify(forecastRepository).findByKey(snapshot.id(), MODEL_NAME,
                "gold-direction-session-prompt-v2", GoldForecastRule.RULE_VERSION);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("最终查询跨过截止线，不能沿用查询前的发布时间保存")
    void rejectsSlowLookup() {
        lookupRace(false);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("模型返回后的查询期间目标变为已知，不能以旧时钟避开确认")
    void rejectsKnownDuringLookup() {
        lookupRace(true);
    }

    private void lookupRace(boolean known) {
        var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenAnswer(call -> time.get());
        service = serviceAt(clock);
        var snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class))).thenReturn(prompt());
        when(gateway.generate(prompt())).thenReturn(generated());
        var queries = new java.util.concurrent.atomic.AtomicInteger();
        when(bars.findNext("XAUUSD", "twelve_data", LocalDate.parse("2026-08-26")))
                .thenAnswer(call -> {
                    if (queries.incrementAndGet() == 1) return Optional.empty();
                    time.set(known ? NOW.plusSeconds(1) : Instant.parse("2026-08-27T21:00:00Z"));
                    if (!known) return Optional.empty();
                    var receipt = OffsetDateTime.ofInstant(time.get(), ZoneOffset.UTC);
                    return Optional.of(new com.opspilot.ai.marketdata.GoldDailyBar("XAUUSD",
                            LocalDate.parse("2026-08-27"), new BigDecimal("10"), new BigDecimal("12"),
                            new BigDecimal("8"), new BigDecimal("11"), "usd", "troy_ounce", "twelve_data", receipt,
                            new com.opspilot.ai.marketdata.GoldBarConfirmation(
                                    com.opspilot.ai.marketdata.GoldBarConfirmation.SOURCE,
                                    LocalDate.parse("2026-08-27"), receipt, "a".repeat(64))));
                });
        lenient().when(forecastRepository.saveIfAbsent(any())).thenAnswer(call ->
                new SaveGoldForecastResult(call.getArgument(0), true));
        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldPublicationException.class)
                .hasMessageContaining(known ? "已知" : "结束");
        verify(gateway).generate(prompt());
        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("目标候选时段已结束，即使数据库没有后续行情也不能新建")
    void rejectsExpiredPublication() {
        service = serviceAt(Clock.fixed(Instant.parse("2026-08-27T21:00:00Z"), ZoneOffset.UTC));
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        lenient().when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class))).thenReturn(prompt());
        lenient().when(gateway.generate(prompt())).thenReturn(generated());
        lenient().when(forecastRepository.saveIfAbsent(any())).thenAnswer(invocation ->
                new SaveGoldForecastResult(invocation.getArgument(0), true));

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .hasMessageContaining("目标").hasMessageContaining("结束");
        verifyNoInteractions(promptBuilder, gateway, validator);
        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("模型调用跨过目标结束线，已返回的答案也不能保存")
    void rejectsCrossingDeadline() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        // 快照核验、初次查询前后均未过期，模型返回后的核验恰好到截止线。
        when(clock.instant()).thenReturn(NOW, NOW, NOW, Instant.parse("2026-08-27T21:00:00Z"));
        service = serviceAt(clock);
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class))).thenReturn(prompt());
        when(gateway.generate(prompt())).thenReturn(generated());
        lenient().when(forecastRepository.saveIfAbsent(any())).thenAnswer(invocation ->
                new SaveGoldForecastResult(invocation.getArgument(0), true));

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .hasMessageContaining("目标").hasMessageContaining("结束");
        verify(gateway).generate(prompt());
        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    private GoldForecastGenerationService serviceAt(Clock clock) {
        return new GoldForecastGenerationService(snapshotRepository, forecastRepository,
                promptBuilder, gateway, validator,
                new GoldForecastDataFreshnessPolicy(Clock.fixed(NOW, ZoneOffset.UTC)),
                new GoldForecastProperties(MODEL_NAME), clock, publication());
    }

    @BeforeEach
    void setUp() {
        service = new GoldForecastGenerationService(
                snapshotRepository,
                forecastRepository,
                promptBuilder,
                gateway,
                validator,
                new GoldForecastDataFreshnessPolicy(
                        Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                new GoldForecastProperties(MODEL_NAME),
                Clock.fixed(NOW, ZoneOffset.UTC),
                publication()
        );
    }

    private GoldForecastPublicationPolicy publication() {
        return new GoldForecastPublicationPolicy(new ConfiguredGoldTradingCalendar(),
                bars);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("正式v3快照缺窗口依据时拒绝，不读取幂等预测或调用模型")
    void rejectsMissingInput() {
        StoredGoldResearchSnapshot source = confirmed();
        var value = source.snapshot();
        StoredGoldResearchSnapshot snapshot = new StoredGoldResearchSnapshot(source.id(),
                new GoldResearchSnapshot(value.analysisDate(), value.latestGoldDate(), value.latestRealRateDate(),
                        value.latestDollarIndexDate(), value.gold(), value.realRate(), value.dollarIndex(),
                        value.realRateAssessment(), value.dollarIndexAssessment(), value.researchVersion(),
                        value.disclaimer()), source.createdAt());
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        lenient().when(forecastRepository.findByKey(snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.FORMAL_VERSION, GoldForecastRule.RULE_VERSION))
                .thenReturn(Optional.of(existingForecast(snapshot)));

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastSnapshotException.class);
        verifyNoInteractions(forecastRepository, promptBuilder, gateway, validator);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("核验时刻晚于快照保存时刻时拒绝，不用后来依据升级旧快照")
    void rejectsProofAfterSnapshot() {
        StoredGoldResearchSnapshot source = confirmed();
        StoredGoldResearchSnapshot snapshot = new StoredGoldResearchSnapshot(source.id(),
                GoldSnapshotFixtures.withInput(source.snapshot(), source.snapshot().latestGoldDate(),
                        source.snapshot().researchVersion(), source.createdAt().plusSeconds(1)), source.createdAt());
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        lenient().when(forecastRepository.findByKey(snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.FORMAL_VERSION, GoldForecastRule.RULE_VERSION))
                .thenReturn(Optional.of(existingForecast(snapshot)));

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastSnapshotException.class);
        verifyNoInteractions(forecastRepository, promptBuilder, gateway, validator);
    }

    @Test
    void rejectsMissingSnapshotBeforeOtherWork() {
        when(snapshotRepository.findById(GoldForecastTestFixtures.SNAPSHOT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(GoldForecastTestFixtures.SNAPSHOT_ID))
                .isInstanceOf(GoldResearchSnapshotNotFoundException.class);

        verifyNoInteractions(forecastRepository, promptBuilder, gateway, validator);
    }

    @Test
    void rejectsNonOfficialSnapshotBeforeIdempotencyLookup() {
        StoredGoldResearchSnapshot snapshot = snapshotWithVersion("gold-real-rate-v1");
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastSnapshotException.class)
                .hasMessageContaining("gold-real-rate-v1");

        verifyNoInteractions(forecastRepository, promptBuilder, gateway, validator);
    }

    @Test
    void rejectsStaleGoldPriceBeforeModelCall() {
        StoredGoldResearchSnapshot snapshot = snapshotWithDates(
                "2026-08-23",
                "2026-08-25",
                "2026-08-21"
        );
        when(snapshotRepository.findById(snapshot.id()))
                .thenReturn(Optional.of(snapshot));
        when(forecastRepository.findByKey(
                snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.FORMAL_VERSION,
                GoldForecastRule.RULE_VERSION
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .hasMessageContaining("黄金价格")
                .hasMessageContaining("过期");

        verifyNoInteractions(promptBuilder, gateway, validator);
    }

    @Test
    void returnsExistingForecastEvenWhenSnapshotDataIsNowStale() {
        StoredGoldResearchSnapshot snapshot = snapshotWithDates(
                "2026-08-23",
                "2026-08-19",
                "2026-08-19"
        );
        StoredGoldDirectionForecast existing = existingForecast(snapshot);
        when(snapshotRepository.findById(snapshot.id()))
                .thenReturn(Optional.of(snapshot));
        when(forecastRepository.findByKey(
                snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.FORMAL_VERSION,
                GoldForecastRule.RULE_VERSION
        )).thenReturn(Optional.of(existing));

        SaveGoldForecastResult result = service.generate(snapshot.id());

        assertThat(result).isEqualTo(
                new SaveGoldForecastResult(existing, false)
        );
        verifyNoInteractions(promptBuilder, gateway, validator);
    }

    @Test
    void returnsExistingForecastWithoutBuildingPromptOrCallingModel() {
        StoredGoldResearchSnapshot snapshot = confirmed();
        StoredGoldDirectionForecast existing = existingForecast(snapshot);
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        when(forecastRepository.findByKey(
                snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.FORMAL_VERSION,
                GoldForecastRule.RULE_VERSION
        )).thenReturn(Optional.of(existing));

        SaveGoldForecastResult result = service.generate(snapshot.id());

        assertThat(result).isEqualTo(new SaveGoldForecastResult(existing, false));
        verifyNoInteractions(promptBuilder, gateway, validator);
        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    void generatesValidatesAndSavesPendingForecastInOrder() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        GoldForecastPrompt prompt = prompt();
        GeneratedGoldForecast generated = generated();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class))).thenReturn(prompt);
        when(gateway.generate(prompt)).thenReturn(generated);
        when(forecastRepository.saveIfAbsent(any())).thenAnswer(invocation ->
                new SaveGoldForecastResult(invocation.getArgument(0), true)
        );

        SaveGoldForecastResult result = service.generate(snapshot.id());

        InOrder order = inOrder(
                snapshotRepository, forecastRepository,
                promptBuilder, gateway, validator
        );
        order.verify(snapshotRepository).findById(snapshot.id());
        order.verify(forecastRepository).findByKey(
                snapshot.id(), MODEL_NAME, prompt.version(), GoldForecastRule.RULE_VERSION
        );
        order.verify(promptBuilder).build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class));
        order.verify(gateway).generate(prompt);
        order.verify(validator).validateFormal(generated.content());
        order.verify(forecastRepository).saveIfAbsent(any());

        ArgumentCaptor<StoredGoldDirectionForecast> captor =
                ArgumentCaptor.forClass(StoredGoldDirectionForecast.class);
        verify(forecastRepository).saveIfAbsent(captor.capture());
        StoredGoldDirectionForecast candidate = captor.getValue();
        assertThat(result.created()).isTrue();
        assertThat(candidate.snapshotId()).isEqualTo(snapshot.id());
        assertThat(candidate.baseDate()).isEqualTo(snapshot.snapshot().latestGoldDate());
        assertThat(candidate.basePrice()).isEqualByComparingTo("2515.75");
        assertThat(candidate.predictedDirection()).isEqualTo(ForecastDirection.BULLISH);
        assertThat(candidate.reasoning()).isEqualTo("双因子共同支持黄金。");
        assertThat(candidate.invalidationConditions()).containsExactly("实际利率转为持续上行");
        assertThat(candidate.modelName()).isEqualTo(MODEL_NAME);
        assertThat(candidate.promptVersion()).isEqualTo(prompt.version());
        assertThat(candidate.promptHash()).isEqualTo(prompt.sha256());
        assertThat(candidate.forecastRuleVersion()).isEqualTo(GoldForecastRule.RULE_VERSION);
        assertThat(candidate.rawResponse()).isEqualTo(generated.rawResponse());
        assertThat(candidate.status()).isEqualTo(ForecastStatus.PENDING);
        assertThat(candidate.targetDate()).isNull();
        assertThat(candidate.targetPrice()).isNull();
        assertThat(candidate.actualReturn()).isNull();
        assertThat(candidate.actualDirection()).isNull();
        assertThat(candidate.hit()).isNull();
        assertThat(candidate.resolvedAt()).isNull();
        assertThat(candidate.createdAt()).isEqualTo(
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void doesNotSaveWhenModelCallFails() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class))).thenReturn(prompt());
        when(gateway.generate(prompt())).thenThrow(
                new GoldForecastAiUnavailableException("上游不可用", new IllegalStateException())
        );

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(GoldForecastAiUnavailableException.class);

        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    void doesNotSaveWhenModelResponseCannotBeParsed() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class))).thenReturn(prompt());
        when(gateway.generate(prompt())).thenThrow(
                new InvalidGoldForecastAiResponseException("响应不是合法 JSON", new IllegalArgumentException())
        );

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastAiResponseException.class);

        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    void doesNotSaveWhenForecastViolatesSafetyRules() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        when(promptBuilder.build(org.mockito.ArgumentMatchers.eq(snapshot), any(GoldForecastTiming.class))).thenReturn(prompt());
        when(gateway.generate(prompt())).thenReturn(generated());
        doThrow(new UnsafeGoldForecastException("包含交易建议"))
                .when(validator).validateFormal(generated().content());

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(UnsafeGoldForecastException.class);

        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    private StoredGoldResearchSnapshot prepareNewForecast() {
        StoredGoldResearchSnapshot snapshot = confirmed();
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        when(forecastRepository.findByKey(
                snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.FORMAL_VERSION,
                GoldForecastRule.RULE_VERSION
        )).thenReturn(Optional.empty());
        return snapshot;
    }

    private GoldForecastPrompt prompt() {
        return new GoldForecastPrompt(
                GoldForecastPromptBuilder.FORMAL_VERSION,
                "正式黄金方向预测提示词",
                "a".repeat(64)
        );
    }

    private GeneratedGoldForecast generated() {
        return new GeneratedGoldForecast(
                MODEL_NAME,
                "{\"direction\":\"BULLISH\"}",
                new GoldDirectionForecastContent(
                        ForecastDirection.BULLISH,
                        "双因子共同支持黄金。",
                        List.of("实际利率转为持续上行"), ForecastSignal.PREDICTED
                ), MODEL_NAME
        );
    }

    private StoredGoldResearchSnapshot snapshotWithVersion(String version) {
        StoredGoldResearchSnapshot source = confirmed();
        GoldResearchSnapshot value = source.snapshot();
        return new StoredGoldResearchSnapshot(
                source.id(),
                new GoldResearchSnapshot(
                        value.analysisDate(), value.latestGoldDate(),
                        value.latestRealRateDate(), value.latestDollarIndexDate(),
                        value.gold(), value.realRate(), value.dollarIndex(),
                        value.realRateAssessment(), value.dollarIndexAssessment(),
                        version, value.disclaimer(), value.input()
                ),
                source.createdAt()
        );
    }

    private StoredGoldResearchSnapshot snapshotWithDates(
            String goldDate,
            String realRateDate,
            String dollarIndexDate
    ) {
        StoredGoldResearchSnapshot source = confirmed();
        GoldResearchSnapshot value = GoldSnapshotFixtures.withInput(source.snapshot(),
                LocalDate.parse(goldDate), source.snapshot().researchVersion(), source.createdAt());

        return new StoredGoldResearchSnapshot(
                source.id(),
                new GoldResearchSnapshot(
                        value.analysisDate(),
                        LocalDate.parse(goldDate),
                        LocalDate.parse(realRateDate),
                        LocalDate.parse(dollarIndexDate),
                        value.gold(), value.realRate(), value.dollarIndex(),
                        value.realRateAssessment(),
                        value.dollarIndexAssessment(),
                        value.researchVersion(), value.disclaimer(), value.input()
                ),
                source.createdAt()
        );
    }

    private StoredGoldResearchSnapshot confirmed() {
        StoredGoldResearchSnapshot source = GoldForecastTestFixtures.snapshot("2515.75");
        return new StoredGoldResearchSnapshot(source.id(), GoldSnapshotFixtures.withInput(
                source.snapshot(), source.snapshot().latestGoldDate(), "gold-multifactor-contiguous-v4", source.createdAt()),
                source.createdAt());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("旧v3快照即使有匹配留痕和幂等记录，也不能用于新协议生成")
    void rejectsPreContinuitySnapshot() {
        StoredGoldResearchSnapshot source = confirmed();
        StoredGoldResearchSnapshot snapshot = new StoredGoldResearchSnapshot(source.id(),
                GoldSnapshotFixtures.withInput(source.snapshot(), source.snapshot().latestGoldDate(),
                        "gold-multifactor-confirmed-v3", source.createdAt()), source.createdAt());
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        lenient().when(forecastRepository.findByKey(snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.FORMAL_VERSION, GoldForecastRule.RULE_VERSION))
                .thenReturn(Optional.of(existingForecast(snapshot)));
        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastSnapshotException.class);
        verifyNoInteractions(forecastRepository, promptBuilder, gateway, validator);
    }

    private StoredGoldDirectionForecast existingForecast(
            StoredGoldResearchSnapshot snapshot
    ) {
        return new StoredGoldDirectionForecast(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                snapshot.id(), snapshot.snapshot().latestGoldDate(),
                new BigDecimal("2515.75"), ForecastDirection.NEUTRAL,
                "等待更多证据。", List.of("因子方向发生明显变化"),
                MODEL_NAME, GoldForecastPromptBuilder.FORMAL_VERSION,
                "a".repeat(64), GoldForecastRule.RULE_VERSION,
                "{\"direction\":\"NEUTRAL\"}", ForecastStatus.PENDING,
                null, null, null, null, null, null,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }
}
