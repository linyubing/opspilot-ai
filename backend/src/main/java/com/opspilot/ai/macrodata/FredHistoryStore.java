package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取一批不可变的 FRED 历史版本输入。 */
@Component
public class FredHistoryStore {
    public static final String POLICY = "fred-known-before-day-v1";
    private final ObjectMapper json;
    private final String directory;

    public FredHistoryStore(ObjectMapper json,
            @Value("${opspilot.forecast.gold.history-dir:${FRED_HISTORY_DIR:}}") String directory) {
        this.json = json;
        this.directory = directory;
    }

    public Batch load() {
        if (directory == null || directory.isBlank()) {
            throw new MacroDataUnavailableException("请配置 FRED_HISTORY_DIR，实验需要真实历史版本归档");
        }
        try {
            Map<String, FredHistory> series = new LinkedHashMap<>();
            for (String id : List.of("DFII10", "DTWEXBGS")) {
                Path path = Path.of(directory).resolve(id + ".json");
                if (Files.size(path) > 64 * 1024 * 1024) throw new IllegalArgumentException("历史文件过大");
                series.put(id, new FredHistory(id, Files.readAllBytes(path), json));
            }
            FredHistory a = series.get("DFII10"), b = series.get("DTWEXBGS");
            if (!a.start.equals(b.start) || !a.end.equals(b.end)
                    || !a.observationStart.equals(b.observationStart) || !a.observationEnd.equals(b.observationEnd)) {
                throw new IllegalArgumentException("两个序列的请求覆盖范围不同");
            }
            return new Batch(series);
        } catch (Exception exception) {
            // 对外不暴露本地路径或原始 JSON；不能降级使用当前修订值。
            throw new MacroDataUnavailableException("FRED 历史版本归档不完整或格式无效，请重新采集完整批次", exception);
        }
    }

    /** 当前实验冻结的两条序列与来源指纹，不受后续文件更换影响。 */
    public static final class Batch {
        private final Map<String, FredHistory> series;
        private final Map<String, String> metadata;
        private Batch(Map<String, FredHistory> series) {
            this.series = Map.copyOf(series);
            Map<String, String> info = new LinkedHashMap<>();
            info.put("policy", POLICY);
            for (FredHistory history : series.values()) {
                info.put(history.series + ".sha256", history.hash);
                info.put(history.series + ".start", history.start.toString());
                info.put(history.series + ".end", history.end.toString());
            }
            metadata = Map.copyOf(info);
        }
        public List<MacroObservation> recent(String id, LocalDate day, int limit) {
            FredHistory history = series.get(id);
            if (history == null) throw new IllegalArgumentException("不支持的宏观序列");
            return history.recent(day, limit);
        }
        public Map<String, String> metadata() { return metadata; }
    }
}
