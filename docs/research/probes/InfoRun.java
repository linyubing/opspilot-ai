package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 只读审计冻结黄金日期的真实宏观版本窗口，不训练、不改正式预测。 */
public class InfoRun {
    record Latest(LocalDate date, String value, UUID id) {}
    record Window(int count, Latest latest, String windowHash) {}
    record Row(LocalDate date, Map<String, Window> series) {}
    static final Path TREE = Path.of("../docs/research/2026-10-03-tree-check.json");

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("需要新结果路径和完整Git哈希");
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("不覆盖已有审计结果");
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var reference = json.readTree(TREE.toFile());
        if (!reference.path("version").asText().equals("ohlc-full-history-xgboost-v1")
                || reference.path("promotionAllowed").asBoolean() || reference.path("inputs").size() != 4361)
            throw new IllegalStateException("冻结黄金参照不符");
        var batch = new FredHistoryStore(json, System.getenv("FRED_HISTORY_DIR")).load();
        List<Row> rows = new ArrayList<>(); LocalDate previous = null;
        for (JsonNode input : reference.path("inputs")) {
            LocalDate day = LocalDate.parse(input.path("date").asText());
            if ((previous != null && !day.isAfter(previous)) || !day.isBefore(LocalDate.parse("2024-11-11")))
                throw new IllegalStateException("冻结日期顺序或范围错误");
            Map<String, Window> series = new LinkedHashMap<>();
            for (String id : List.of("DFII10", "DTWEXBGS")) {
                var observations = batch.recent(id, day, 21);
                StringBuilder text = new StringBuilder();
                for (var item : observations) text.append(item.observationDate()).append('|').append(item.id())
                        .append('|').append(item.value().toPlainString()).append('\n');
                Latest latest = observations.isEmpty() ? null : new Latest(observations.getFirst().observationDate(),
                        observations.getFirst().value().toPlainString(), observations.getFirst().id());
                series.put(id, new Window(observations.size(), latest, sha(text.toString().getBytes(StandardCharsets.UTF_8))));
            }
            rows.add(new Row(day, series)); previous = day;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", "gold-macro-availability-v1"); report.put("gitCommit", args[1]);
        report.put("createdAt", OffsetDateTime.now()); report.put("promotionAllowed", false);
        report.put("treeHash", sha(Files.readAllBytes(TREE))); report.put("macroInput", batch.metadata());
        report.put("cutoffExclusive", "2024-11-11"); report.put("windowLimit", 21);
        Map<String, String> sources = new LinkedHashMap<>();
        for (String file : List.of("docs/research/probes/InfoRun.java", "docs/research/probes/InfoMath.cjs",
                "docs/research/probes/InfoMathChecks.cjs", "docs/research/probes/verify-info.cjs",
                "backend/src/main/java/com/opspilot/ai/macrodata/FredHistoryStore.java",
                "backend/src/main/java/com/opspilot/ai/macrodata/FredHistory.java",
                "backend/src/main/java/com/opspilot/ai/macrodata/MacroObservation.java"))
            sources.put(file, sha(Files.readAllBytes(Path.of("../" + file))));
        report.put("sourceHashes", sources);
        report.put("protocolHash", sha(Files.readAllBytes(Path.of("../docs/research/2026-10-03-info-protocol.md"))));
        report.put("rows", rows);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("INFO_AUDIT_EXPORTED " + rows.size());
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
