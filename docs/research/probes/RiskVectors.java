package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** 只读导出真实VIX窗口及四特征；缺失不补值，不访问留出集。 */
public final class RiskVectors {
    record Window(String date, int count, String latest, String hash) {}
    record Vector(String date, String target, double[] values) {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需要真实VIX归档与新的target输出路径");
        Path target = Path.of("target").toRealPath(), out = Path.of(args[1]).toAbsolutePath().normalize();
        if (!out.startsWith(target) || out.equals(target) || Files.exists(out)
                || !out.getParent().toRealPath().startsWith(target))
            throw new IllegalArgumentException("输出必须是target内新的普通路径");
        ObjectMapper json = new ObjectMapper();
        byte[] source = Files.readAllBytes(Path.of(args[0]));
        FredHistory history = new FredHistory("VIXCLS", source, json);
        Path treePath = Path.of("../docs/research/2026-10-03-tree-check.json");
        byte[] treeBytes = Files.readAllBytes(treePath);
        var tree = json.readTree(treeBytes);
        if (tree.path("inputs").size() != 4361 || tree.path("promotionAllowed").asBoolean())
            throw new IllegalArgumentException("冻结黄金参照不一致");
        List<Window> windows = new ArrayList<>(); List<Vector> vectors = new ArrayList<>();
        for (var node : tree.path("inputs")) {
            LocalDate day = LocalDate.parse(node.path("date").asText());
            String end = node.path("target").asText();
            LocalDate labelDay = LocalDate.parse(end);
            if (!labelDay.isAfter(day) || !labelDay.isBefore(LocalDate.parse("2024-11-11")))
                throw new IllegalArgumentException("禁止读取未隔离目标");
            var rows = history.recent(day, 21); StringBuilder text = new StringBuilder();
            for (var row : rows) text.append(row.observationDate()).append('|').append(row.id())
                    .append('|').append(row.value().toPlainString()).append('\n');
            windows.add(new Window(day.toString(), rows.size(), rows.isEmpty() ? null
                    : rows.getFirst().observationDate().toString(), sha(text.toString().getBytes(StandardCharsets.UTF_8))));
            double[] values = rows.size() == 21 ? VixFeatures.compute(rows.stream()
                    .mapToDouble(row -> row.value().doubleValue()).toArray()) : null;
            vectors.add(new Vector(day.toString(), end, values));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "vix-known-before-day-four-v1");
        result.put("sourceHash", history.hash); result.put("treeHash", sha(treeBytes));
        result.put("promotionAllowed", false);
        result.put("featureNames", List.of("vixLevel", "vixChange1", "vixChange5", "vixChange20"));
        result.put("protocolHash", sha(Files.readAllBytes(Path.of("../docs/research/2026-10-03-vix-protocol.md"))));
        Map<String, String> code = new LinkedHashMap<>();
        for (String name : List.of("RiskVectors.java", "VixFeatures.java", "VixFeatureChecks.java"))
            code.put(name, sha(Files.readAllBytes(Path.of("../docs/research/probes/" + name))));
        code.put("FredHistory.java", sha(Files.readAllBytes(Path.of("src/main/java/com/opspilot/ai/macrodata/FredHistory.java"))));
        result.put("codeHashes", code); result.put("windows", windows); result.put("vectors", vectors);
        Files.writeString(out, json.writeValueAsString(result), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("VIX_VECTORS=" + vectors.size());
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
