package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.analysis.*;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.macrodata.*;
import com.opspilot.ai.marketdata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 显式启用的真实归档验收，只读原训练区间，不查询最终留出期价格或标签。 */
@EnabledIfEnvironmentVariable(named = "VERIFY_FRED_HISTORY", matches = "true")
class FredHistoryArchiveLiveTests {
    @Test
    void reproducesVerifiedSamples() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var history = new FredHistoryStore(json, System.getenv("FRED_HISTORY_DIR"));
        List<GoldDailyBar> bars = new ArrayList<>();
        try (var conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/opspilot_ai", "postgres",
                System.getenv("OPSPILOT_DB_PASSWORD"))) {
            conn.setReadOnly(true); conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement("""
                    select symbol, price_date, open_price, high_price, low_price, close_price,
                           currency, unit, provider, collected_at
                    from gold_daily_bar where symbol = 'XAUUSD' and provider = 'twelve_data'
                        and price_date < date '2024-11-11' order by price_date
                    """); var rs = stmt.executeQuery()) {
                while (rs.next()) bars.add(new GoldDailyBar(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class)));
            }
            conn.rollback();
        }
        assertThat(bars).hasSize(4382);
        var gold = mock(GoldDailyBarRepository.class);
        when(gold.findAll("XAUUSD", "twelve_data")).thenReturn(bars);
        when(gold.findRecent(eq("XAUUSD"), eq("twelve_data"), any(LocalDate.class), anyInt()))
                .thenAnswer(call -> bars.stream().filter(b -> !b.priceDate().isAfter(call.getArgument(2)))
                        .sorted(Comparator.comparing(GoldDailyBar::priceDate).reversed()).limit((int) call.getArgument(3)).toList());
        var latest = mock(MacroObservationRepository.class);
        var snapshots = new GoldResearchSnapshotService(gold, latest, new RealRateFactorEvaluator(), new DollarIndexFactorEvaluator());
        GoldDataset dataset = new GoldDatasetBuilder(gold, snapshots, new GoldForecastRule(), new GoldFeatureCalculator(), history)
                .build(ForecastHorizon.NEXT_DAY);
        verifyNoInteractions(latest);
        assertThat(dataset.samples()).hasSize(1500);
        assertThat(dataset.skippedCount()).isEqualTo(2861);
        assertThat(dataset.samples().getFirst().asOfDate()).isEqualTo("2019-02-05");
        assertThat(dataset.samples().getLast().asOfDate()).isEqualTo("2024-11-07");

        // 独立复算上一轮的纯内容摘要；新指纹还包含来源，不能拿新旧指纹直接对比。
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (GoldSample sample : dataset.samples()) {
            for (String field : List.of(sample.asOfDate().toString(), sample.targetDate().toString(),
                    sample.label().name(), sample.horizon().name())) add(digest, field);
            for (String name : sample.features().values().keySet().stream().sorted().toList()) {
                add(digest, name); add(digest, String.valueOf(sample.features().values().get(name)));
            }
        }
        String contentHash = HexFormat.of().formatHex(digest.digest());
        assertThat(contentHash).isEqualTo("4ce352acbb854dd4e7e6d2de67c3426f0f62f360b76cf267559d57df07ea0dd3");
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/fred-product-verification.json").toFile(), Map.of(
                "verifiedAt", OffsetDateTime.now().toString(), "samples", dataset.samples().size(),
                "skipped", dataset.skippedCount(), "contentHash", contentHash,
                "datasetHash", new GoldDatasetFingerprint().hash(dataset), "macroInput", dataset.macroInput(),
                "first", dataset.samples().getFirst().asOfDate().toString(), "last", dataset.samples().getLast().asOfDate().toString(),
                "holdoutRead", false));
    }
    private void add(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
}
