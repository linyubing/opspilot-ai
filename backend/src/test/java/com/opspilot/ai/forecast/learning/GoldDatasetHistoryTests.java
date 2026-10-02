package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.analysis.*;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.macrodata.*;
import com.opspilot.ai.marketdata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 完整计算链的合成边界夹具；不作为真实行情或实验成绩。 */
class GoldDatasetHistoryTests {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private final LocalDate base = LocalDate.parse("2024-11-05");

    @Test
    @DisplayName("实验采用基准日前已公布版本，绕过最新宏观仓库且不受后来修订影响")
    void buildsKnownFeatures() throws Exception {
        write("DFII10", "2.04", "9.99"); write("DTWEXBGS", "125.0403", "124.8076");
        var gold = mock(GoldDailyBarRepository.class);
        var macro = mock(MacroObservationRepository.class);
        var rows = bars();
        when(gold.findAll("XAUUSD", "twelve_data")).thenReturn(rows);
        when(gold.findRecent(eq("XAUUSD"), eq("twelve_data"), eq(base), anyInt())).thenReturn(rows.subList(0, 21));
        when(macro.findRecent(anyString(), eq(base), anyInt())).thenReturn(List.of());
        var snapshots = new GoldResearchSnapshotService(gold, macro,
                new RealRateFactorEvaluator(), new DollarIndexFactorEvaluator());
        var store = spy(new FredHistoryStore(json, dir.toString()));
        var builder = new GoldDatasetBuilder(gold, snapshots, new GoldForecastRule(), new GoldFeatureCalculator(), store);

        GoldDataset first = builder.build(ForecastHorizon.NEXT_DAY);
        assertThat(first.macroInput()).containsEntry("policy", FredHistoryStore.POLICY)
                .containsKeys("DFII10.sha256", "DTWEXBGS.sha256");
        assertThat(first.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.features().values()).containsEntry("real_rate", 2.04)
                    .containsEntry("real_rate_age", 4.0).containsEntry("dollar_age", 4.0);
            assertThat(sample.asOfDate()).isEqualTo(base);
            assertThat(sample.targetDate()).isEqualTo(base.plusDays(1));
        });
        verify(store).load();
        verifyNoInteractions(macro);
        write("DFII10", "2.04", "100.00");
        GoldDataset second = builder.build(ForecastHorizon.NEXT_DAY);
        assertThat(second.samples()).isEqualTo(first.samples());
    }

    private List<GoldDailyBar> bars() {
        List<GoldDailyBar> result = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            LocalDate day = base.minusDays(20).plusDays(i);
            BigDecimal close = BigDecimal.valueOf(2000 + i);
            result.add(new GoldDailyBar("XAUUSD", day, close, close.add(BigDecimal.ONE),
                    close.subtract(BigDecimal.ONE), close, "USD", "troy_ounce", "twelve_data",
                    OffsetDateTime.parse("2026-09-30T00:00:00Z")));
        }
        return result;
    }

    private void write(String series, String initial, String revision) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String day = LocalDate.parse("2024-11-01").minusDays(i).toString();
            rows.add(Map.of("date", day, "realtime_start", "2024-11-04", "realtime_end", "2024-11-05", "value", initial));
            rows.add(Map.of("date", day, "realtime_start", "2024-11-06", "realtime_end", "2024-11-08", "value", revision));
        }
        json.writeValue(dir.resolve(series + ".json").toFile(), Map.of(
                "series", series, "fetchedAt", "2026-09-30T00:00:00Z", "outputType", 1,
                "observationStart", "2024-10-01", "observationEnd", "2024-11-08",
                "realtimeStart", "2024-10-01", "realtimeEnd", "2024-11-08",
                "count", rows.size(), "observations", rows,
                "chunks", List.of(Map.of("start", "2024-10-01", "end", "2024-11-08", "count", rows.size()))));
    }
}
