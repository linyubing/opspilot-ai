package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** 用生产历史版本解析器独立复核CPI可得性；不接入正式模型。 */
public class CheckCpiHistory {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("需历史归档、冻结输入、可得性结果");
        var json = new ObjectMapper();
        var history = new FredHistory("CPIAUCNS", Files.readAllBytes(Path.of(args[0])), json);
        var result = json.readTree(Path.of(args[2]).toFile());
        if (!history.hash.equals(result.path("archiveSha256").asText())) throw new IllegalStateException("版本归档摘要不符");
        int total = 0, covered = 0;
        var months = new HashSet<LocalDate>();
        for (var sample : json.readTree(Path.of(args[1]).toFile()).path("samples")) {
            total++;
            var rows = history.recent(LocalDate.parse(sample.path("asOfDate").asText()), 13);
            if (rows.isEmpty()) continue;
            var latest = rows.getFirst();
            boolean prior = rows.stream().anyMatch(r -> r.observationDate().equals(latest.observationDate().minusMonths(1)));
            boolean year = rows.stream().anyMatch(r -> r.observationDate().equals(latest.observationDate().minusYears(1)));
            if (prior && year) { covered++; months.add(latest.observationDate()); }
        }
        if (total != 1268 || covered != result.path("covered").asInt()
                || months.size() != result.path("distinctObservationMonths").asInt()) throw new IllegalStateException("覆盖复算不符");
        if (!history.recent(LocalDate.of(2024, 10, 10), 1).getFirst().observationDate().equals(LocalDate.of(2024, 8, 1)))
            throw new IllegalStateException("发布当天误用了新值");
        if (!history.recent(LocalDate.of(2024, 10, 11), 1).getFirst().observationDate().equals(LocalDate.of(2024, 9, 1)))
            throw new IllegalStateException("发布后一天未选中已知版本");
        System.out.println("生产解析器独立核验通过：覆盖=" + covered + "/" + total + "，不同观测月=" + months.size() + "，发布日D-1边界一致");
    }
}
