package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 用真实公布值和明确的边界夹具检验历史版本选择，不作为行情样本使用。 */
class FredHistoryTests {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("周发布美元值不能提前可见，后续修订不能污染旧日期")
    void usesKnownVersion() throws Exception {
        write("DTWEXBGS", List.of(
                row("2024-10-25", "2024-10-28", "2024-11-08", "124.8418"),
                row("2024-11-01", "2024-11-04", "2024-11-06", "125.0403"),
                row("2024-11-01", "2024-11-07", "2024-11-08", "124.8076")));
        writeRate();
        var batch = store().load();
        assertThat(batch.recent("DTWEXBGS", day("2024-11-04"), 21))
                .extracting(MacroObservation::observationDate).containsExactly(day("2024-10-25"));
        assertThat(batch.recent("DTWEXBGS", day("2024-11-05"), 21).getFirst().value())
                .isEqualByComparingTo("125.0403");
        assertThat(batch.recent("DTWEXBGS", day("2024-11-07"), 21).getFirst().value())
                .isEqualByComparingTo("125.0403");
        assertThat(batch.recent("DTWEXBGS", day("2024-11-08"), 21).getFirst().value())
                .isEqualByComparingTo("124.8076");
    }

    @Test
    @DisplayName("当前历史版本缺失时，不回退到旧的非空版本")
    void keepsMissing() throws Exception {
        write("DTWEXBGS", List.of(
                row("2024-11-01", "2024-11-04", "2024-11-05", "125.0403"),
                row("2024-11-01", "2024-11-06", "2024-11-08", ".")));
        writeRate();
        assertThat(store().load().recent("DTWEXBGS", day("2024-11-07"), 21)).isEmpty();
    }

    @Test
    @DisplayName("文件过期必须报错，不能继续拿末尾值运行最新实验")
    void rejectsStaleArchive() throws Exception {
        writeRate(); write("DTWEXBGS", List.of(row("2024-11-01", "2024-11-04", "2024-11-08", "125.0403")));
        assertThatThrownBy(() -> store().load().recent("DFII10", day("2024-11-10"), 21))
                .isInstanceOf(MacroDataUnavailableException.class).hasMessageContaining("覆盖");
    }

    @Test
    @DisplayName("重叠版本无法确定当时值，必须拒绝整个批次")
    void rejectsOverlap() throws Exception {
        writeRate(); write("DTWEXBGS", List.of(
                row("2024-11-01", "2024-11-04", "2024-11-07", "125.0403"),
                row("2024-11-01", "2024-11-07", "2024-11-08", "124.8076")));
        assertThatThrownBy(() -> store().load()).isInstanceOf(MacroDataUnavailableException.class);
    }

    @Test
    @DisplayName("不完整文件和未配置目录不能回退到最新宏观数据")
    void rejectsIncompleteBatch() throws Exception {
        writeRate();
        assertThatThrownBy(() -> store().load()).isInstanceOf(MacroDataUnavailableException.class)
                .hasMessageNotContaining(dir.toString());
        assertThatThrownBy(() -> new FredHistoryStore(json, "").load())
                .isInstanceOf(MacroDataUnavailableException.class);
    }

    @Test
    @DisplayName("一次加载的来源和值冻结，磁盘后续变化不影响当前实验")
    void freezesBatch() throws Exception {
        writeRate(); write("DTWEXBGS", List.of(row("2024-11-01", "2024-11-04", "2024-11-08", "125.0403")));
        var before = store().load();
        write("DTWEXBGS", List.of(row("2024-11-01", "2024-11-04", "2024-11-08", "124.8076")));
        var after = store().load();
        assertThat(before.metadata()).isNotEqualTo(after.metadata());
        assertThat(before.recent("DTWEXBGS", day("2024-11-05"), 21).getFirst().value())
                .isEqualByComparingTo("125.0403");
        assertThatThrownBy(() -> before.metadata().put("policy", "wrong"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("归档声称的条数必须与实际记录相符")
    void rejectsCountMismatch() throws Exception {
        writeRate(); write("DTWEXBGS", List.of(row("2024-11-01", "2024-11-04", "2024-11-08", "125.0403")));
        var file = dir.resolve("DFII10.json").toFile();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(file);
        node.put("count", 2); json.writeValue(file, node);
        assertThatThrownBy(() -> store().load()).isInstanceOf(MacroDataUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"series", "type", "nan", "gap", "chunkCount", "outside", "range", "reversed"})
    @DisplayName("不可信归档声明和畸形区间不能作为实验输入")
    void rejectsInvalidArchive(String problem) throws Exception {
        writeRate(); write("DTWEXBGS", List.of(row("2024-11-01", "2024-11-04", "2024-11-08", "125.0403")));
        var file = dir.resolve("DFII10.json").toFile();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(file);
        var row = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("observations").get(0);
        var chunk = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("chunks").get(0);
        switch (problem) {
            case "series" -> root.put("series", "OTHER");
            case "type" -> root.put("outputType", 4);
            case "nan" -> row.put("value", "NaN");
            case "gap" -> chunk.put("start", "2024-10-02");
            case "chunkCount" -> chunk.put("count", 0);
            case "outside" -> row.put("realtime_end", "2024-11-09");
            case "range" -> root.put("observationEnd", "2024-11-07");
            case "reversed" -> row.put("realtime_end", "2024-11-03");
            default -> throw new AssertionError(problem);
        }
        json.writeValue(file, root);
        assertThatThrownBy(() -> store().load()).isInstanceOf(MacroDataUnavailableException.class);
    }

    private FredHistoryStore store() { return new FredHistoryStore(json, dir.toString()); }
    private void writeRate() throws Exception {
        write("DFII10", List.of(row("2024-11-01", "2024-11-04", "2024-11-08", "2.04")));
    }
    private void write(String series, List<Map<String, String>> rows) throws Exception {
        json.writeValue(dir.resolve(series + ".json").toFile(), Map.of(
                "series", series, "fetchedAt", "2026-09-30T00:00:00Z", "outputType", 1,
                "observationStart", "2024-10-01", "observationEnd", "2024-11-08",
                "realtimeStart", "2024-10-01", "realtimeEnd", "2024-11-08",
                "count", rows.size(), "observations", rows,
                "chunks", List.of(Map.of("start", "2024-10-01", "end", "2024-11-08", "count", rows.size()))));
    }
    private Map<String, String> row(String date, String start, String end, String value) {
        return Map.of("date", date, "realtime_start", start, "realtime_end", end, "value", value);
    }
    private LocalDate day(String value) { return LocalDate.parse(value); }
}
