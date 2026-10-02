package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 按来源的生效/失效区间选择历史观测；采集时间不冒充原始发布时间。 */
final class FredHistory {
    private record Version(LocalDate start, LocalDate end, BigDecimal value) {}
    private record Chunk(LocalDate start, LocalDate end, int count) {}

    final String series;
    final String hash;
    final LocalDate observationStart;
    final LocalDate observationEnd;
    final LocalDate start;
    final LocalDate end;
    private final OffsetDateTime fetchedAt;
    private final NavigableMap<LocalDate, List<Version>> dates;

    FredHistory(String series, byte[] bytes, ObjectMapper json) throws Exception {
        JsonNode root = json.readTree(bytes);
        this.series = series;
        require(series.equals(text(root, "series")), "序列不一致");
        require(root.path("outputType").isIntegralNumber() && root.path("outputType").intValue() == 1,
                "必须使用来源有效区间输出");
        observationStart = date(root, "observationStart");
        observationEnd = date(root, "observationEnd");
        start = date(root, "realtimeStart");
        end = date(root, "realtimeEnd");
        fetchedAt = OffsetDateTime.parse(text(root, "fetchedAt"));
        require(!start.isAfter(end) && !observationStart.isAfter(observationEnd)
                && !end.isAfter(fetchedAt.toLocalDate()), "归档日期范围无效");
        JsonNode rows = root.path("observations");
        require(rows.isArray() && !rows.isEmpty() && root.path("count").isIntegralNumber()
                && root.path("count").longValue() == rows.size(), "版本记录数量不符");

        // 分页/分批必须覆盖声明的版本区间；首次有版本之前允许没有数据。
        LocalDate expectedStart = root.has("firstAvailableVintage")
                ? date(root, "firstAvailableVintage") : start;
        if (expectedStart.isBefore(start)) expectedStart = start;
        List<Chunk> chunks = new ArrayList<>();
        require(root.path("chunks").isArray(), "缺少下载批次信息");
        long count = 0;
        for (JsonNode node : root.path("chunks")) {
            LocalDate from = date(node, "start"), to = date(node, "end");
            require(from.equals(expectedStart) && !to.isBefore(from) && !to.isAfter(end)
                    && node.path("count").isIntegralNumber() && node.path("count").longValue() >= 0
                    && node.path("count").longValue() <= rows.size(), "下载批次不连续或范围无效");
            chunks.add(new Chunk(from, to, node.path("count").intValue()));
            count += node.path("count").longValue();
            expectedStart = to.plusDays(1);
        }
        require(!chunks.isEmpty() && expectedStart.equals(end.plusDays(1)) && count == rows.size(),
                "下载批次未完整结束");

        TreeMap<LocalDate, List<Version>> index = new TreeMap<>(Comparator.reverseOrder());
        int[] chunkCounts = new int[chunks.size()];
        for (JsonNode row : rows) {
            LocalDate day = date(row, "date"), from = date(row, "realtime_start"), to = date(row, "realtime_end");
            require(!day.isBefore(observationStart) && !day.isAfter(observationEnd)
                    && !from.isAfter(to), "观测日期或版本区间无效");
            int owner = -1;
            for (int i = 0; i < chunks.size(); i++) {
                if (!from.isBefore(chunks.get(i).start()) && !to.isAfter(chunks.get(i).end())) owner = i;
            }
            require(owner >= 0, "版本区间超出下载批次");
            chunkCounts[owner]++;
            String raw = text(row, "value");
            BigDecimal value = raw.equals(".") ? null : new BigDecimal(raw);
            require(value == null || Double.isFinite(value.doubleValue()), "观测值不是有限数字");
            require(value == null || !series.equals("DTWEXBGS") || value.signum() > 0, "美元指数必须大于零");
            index.computeIfAbsent(day, ignored -> new ArrayList<>()).add(new Version(from, to, value));
        }
        for (int i = 0; i < chunks.size(); i++) require(chunkCounts[i] == chunks.get(i).count(), "下载批次条数不符");
        for (var entry : index.entrySet()) {
            List<Version> versions = entry.getValue();
            versions.sort(Comparator.comparing(Version::start));
            for (int i = 1; i < versions.size(); i++) {
                require(versions.get(i).start().isAfter(versions.get(i - 1).end()), "历史版本区间重叠");
            }
            entry.setValue(List.copyOf(versions));
        }
        dates = Collections.unmodifiableNavigableMap(index);
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    List<MacroObservation> recent(LocalDate day, int limit) {
        Objects.requireNonNull(day, "预测日期不能为空");
        require(limit > 0, "查询数量必须大于零");
        // ALFRED 没有日内发布时间：采用前一完整日，不声称具备盘中时点精度。
        LocalDate cutoff = day.minusDays(1);
        if (cutoff.isAfter(end) || cutoff.isAfter(observationEnd)) {
            throw new MacroDataUnavailableException("FRED 历史版本未覆盖实验日期，请先更新完整归档");
        }
        if (cutoff.isBefore(start) || cutoff.isBefore(observationStart)) return List.of();
        List<MacroObservation> result = new ArrayList<>();
        for (var entry : dates.entrySet()) {
            if (entry.getKey().isAfter(cutoff)) continue;
            for (Version version : entry.getValue()) {
                if (!cutoff.isBefore(version.start()) && !cutoff.isAfter(version.end())) {
                    if (version.value() != null) {
                        String key = series + "/" + entry.getKey() + "/" + version.start();
                        result.add(new MacroObservation(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                                series, entry.getKey(), version.value(), series.equals("DFII10") ? "percent" : "index",
                                "fred", fetchedAt, null));
                    }
                    break; // 缺失也是当时状态，不能继续找旧值。
                }
            }
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }

    private static LocalDate date(JsonNode node, String field) { return LocalDate.parse(text(node, field)); }
    private static String text(JsonNode node, String field) {
        require(node.path(field).isTextual() && !node.path(field).textValue().isBlank(), "归档字段缺失：" + field);
        return node.path(field).textValue();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
