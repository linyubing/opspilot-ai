package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScalingCheckServiceTests {
    private final ScalingCheckService service = new ScalingCheckService(
            null, null, null, null, null, null, new ForecastEvaluator());

    @Test
    void buildsAndSplitsOnlyOnceForAllProfiles() {
        var builder = mock(GoldDatasetBuilder.class);
        var splitter = mock(TemporalSplitter.class);
        var fingerprint = mock(GoldDatasetFingerprint.class);
        var walk = mock(WalkForwardService.class);
        var git = mock(GitCommitProvider.class);
        var clock = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
        // 这里只验证流程与元数据，不把人工测试样本当成真实实验。
        var values = new HashMap<String, Double>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        var day = LocalDate.of(2024, 1, 1);
        var sample = new GoldSample(day, day.plusDays(1), ForecastHorizon.NEXT_DAY,
                new GoldFeatures(values), ForecastDirection.BULLISH);
        var data = new GoldDataset(List.of(sample), 3);
        var split = new TemporalDataset(List.of(sample), List.of(sample), List.of(sample));
        when(git.getRequired()).thenReturn("abcdef1234567");
        when(builder.build(ForecastHorizon.NEXT_DAY)).thenReturn(data);
        when(splitter.split(data.samples(), ForecastHorizon.NEXT_DAY)).thenReturn(split);
        when(fingerprint.hash(data)).thenReturn("dataset-hash");
        when(walk.predict(same(split), any(FeatureProfile.class), any(GoldTrainer.class)))
                .thenReturn(List.of(row(0, "BULLISH", .6, .3, .1)));

        var report = new ScalingCheckService(builder, splitter, fingerprint, walk, git, clock,
                new ForecastEvaluator()).run(ForecastHorizon.NEXT_DAY);

        verify(builder, times(1)).build(ForecastHorizon.NEXT_DAY);
        verify(splitter, times(1)).split(data.samples(), ForecastHorizon.NEXT_DAY);
        verify(walk, times(9)).predict(same(split), any(FeatureProfile.class), any(GoldTrainer.class));
        for (FeatureProfile profile : FeatureProfile.values()) {
            verify(walk).predict(same(split), eq(profile), argThat(t -> t.name().equals("logistic-v1")));
            verify(walk).predict(same(split), eq(profile), argThat(t -> t.name().equals(TribuoGoldTrainer.VERSION)));
            verify(walk).predict(same(split), eq(profile), argThat(t -> t.name().equals("majority-v1")));
        }
        assertThat(report.results()).extracting(ScalingReport.Result::profile)
                .containsExactly(FeatureProfile.values());
        assertThat(report.gitCommit()).isEqualTo("abcdef1234567");
        assertThat(report.datasetHash()).isEqualTo("dataset-hash");
        assertThat(report.skippedCount()).isEqualTo(3);
        assertThat(report.createdAt().toInstant()).isEqualTo(clock.instant());

        clearInvocations(builder, splitter, walk);
        var windows = new ScalingCheckService(builder, splitter, fingerprint, walk, git, clock,
                new ForecastEvaluator()).windows();
        assertThat(windows).isNotNull();
        verify(builder, times(1)).build(ForecastHorizon.NEXT_DAY);
        verify(splitter, times(1)).split(data.samples(), ForecastHorizon.NEXT_DAY);
        verify(walk, times(12)).predict(same(split), any(FeatureProfile.class), any(GoldTrainer.class));
        assertThat(windows.results()).hasSize(6);
        assertThat(windows.results()).extracting(WindowReport.Result::trainSize)
                .containsExactly(252, 504, 252, 504, 252, 504);
        assertThat(windows.results()).allSatisfy(result -> {
            assertThat(result.full().version()).isEqualTo(TribuoGoldTrainer.VERSION);
            assertThat(result.recent().version()).isEqualTo(TribuoGoldTrainer.VERSION + "-window-" + result.trainSize());
        });
    }

    @Test
    void missingCodeVersionStopsBeforeReadingData() {
        var builder = mock(GoldDatasetBuilder.class);
        var git = mock(GitCommitProvider.class);
        when(git.getRequired()).thenThrow(new ModelExperimentException("代码版本缺失"));
        var check = new ScalingCheckService(builder, null, null, null, git, null, null);
        assertThatThrownBy(() -> check.run(ForecastHorizon.NEXT_DAY))
                .isInstanceOf(ModelExperimentException.class);
        verifyNoInteractions(builder);
    }

    @Test
    void scoresEveryDateAndMatchesSignalDates() {
        // 两个模型信号数相同，但日期不同；基线必须跟随各自日期，不能直接使用全样本基线。
        var raw = List.of(row(0, "BULLISH", .6, .3, .1), row(1, "BEARISH", .3, .4, .3),
                row(2, "NEUTRAL", .2, .6, .2), row(3, "BEARISH", .6, .3, .1));
        var scaled = List.of(row(0, "BULLISH", .45, .35, .2), row(1, "BEARISH", .1, .2, .7),
                row(2, "NEUTRAL", .7, .2, .1), row(3, "BEARISH", .1, .2, .7));
        var base = List.of(row(0, "BULLISH", 1, 0, 0), row(1, "BEARISH", 1, 0, 0),
                row(2, "NEUTRAL", 1, 0, 0), row(3, "BEARISH", 1, 0, 0));

        ScalingReport.Result result = service.compare(FeatureProfile.BASE_16, raw, scaled, base);
        assertThat(result.raw().all().accuracy()).isEqualByComparingTo("0.50");
        assertThat(result.scaled().all().accuracy()).isEqualByComparingTo("0.75");
        assertThat(result.raw().all().coverage()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.scaled().signals().coverage()).isEqualByComparingTo("0.75");
        assertThat(result.raw().signals().accuracy()).isEqualByComparingTo("0.6667");
        assertThat(result.scaled().signals().accuracy()).isEqualByComparingTo("0.6667");
        assertThat(result.rawDateBaseline().sampleCount()).isEqualTo(3);
        assertThat(result.raw().selectedDates().sampleCount()).isEqualTo(3);
        assertThat(result.raw().selectedDates().brierScore()).isEqualByComparingTo("0.5867");
        assertThat(result.raw().signals().brierScore()).isEqualByComparingTo("0.6250");
        assertThat(result.scaled().selectedDates().sampleCount())
                .isEqualTo(result.scaledDateBaseline().sampleCount());
        assertThat(result.rawDateBaseline().accuracy()).isEqualByComparingTo("0.3333");
        assertThat(result.scaledDateBaseline().accuracy()).isEqualByComparingTo("0.0");
        assertThat(result.paired()).isEqualTo(new ScalingReport.Pair(2, 1, 1, 2, 1));
        assertThat(result.blocks()).containsExactly(new ScalingReport.Block(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 4), 4, 2, 3, 1));
    }

    @Test
    void noSignalsKeepFullScoresAndMissingBaseline() {
        var weak = List.of(row(0, "BULLISH", .34, .33, .33));
        var base = List.of(row(0, "BULLISH", 1, 0, 0));
        var result = service.compare(FeatureProfile.BASE_16, weak, weak, base);
        assertThat(result.scaled().all().accuracy()).isEqualByComparingTo("1.0");
        assertThat(result.scaled().signals().coveredCount()).isZero();
        assertThat(result.scaledDateBaseline()).isNull();
        assertThat(result.scaled().selectedDates()).isNull();
        assertThat(result.rawDateBaseline()).isNull();
        assertThat(result.paired().commonSignals()).isZero();
    }

    @Test
    void tiedProbabilitiesNeverChooseUsingActualLabel() {
        var tied = List.of(row(0, "NEUTRAL", .4, .4, .2));
        var result = service.compare(FeatureProfile.BASE_16, tied, tied, tied);
        assertThat(result.scaled().all().accuracy()).isEqualByComparingTo("0.0");
        assertThat(result.scaled().all().confusionMatrix().get(ForecastDirection.NEUTRAL)
                .get(ForecastDirection.BULLISH)).isEqualTo(1);
    }

    @Test
    void refusesMismatchedDatesOrLabels() {
        var first = List.of(row(0, "BULLISH", .6, .3, .1));
        assertThatThrownBy(() -> service.compare(FeatureProfile.BASE_16, first,
                List.of(row(1, "BULLISH", .6, .3, .1)), first))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(FeatureProfile.BASE_16, first,
                List.of(row(0, "BEARISH", .6, .3, .1)), first))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesEmptyOrUnequalSamples() {
        var first = List.of(row(0, "BULLISH", .6, .3, .1));
        assertThatThrownBy(() -> service.compare(FeatureProfile.BASE_16, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(FeatureProfile.BASE_16, first, List.of(), first))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SettledPrediction row(int day, String actual, double up, double neutral, double down) {
        var p = new DirectionProbabilities(up, neutral, down);
        return new SettledPrediction(LocalDate.of(2024, 1, 1).plusDays(day), p,
                new ConfidencePolicy(.55).apply(p), ForecastDirection.valueOf(actual));
    }
}
