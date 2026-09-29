package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.analysis.*;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 一次性真实历史版本对照探针；只读原始数据，不写产品数据库或替换正式预测。 */
public class VintageProbe {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final LocalDate LIMIT = TrainingProbe.LIMIT;
    static final List<LocalDate> STARTS = List.of(LocalDate.parse("2022-02-02"),
            LocalDate.parse("2023-01-04"), LocalDate.parse("2023-12-07"));
    static final List<LocalDate> ENDS = List.of(LocalDate.parse("2023-01-03"),
            LocalDate.parse("2023-12-06"), LocalDate.parse("2024-11-07"));

    record Version(LocalDate date, LocalDate start, LocalDate end, BigDecimal value) {}
    record Scores(ForecastMetrics all, ForecastMetrics signals) {}
    record Compare(FeatureProfile profile, Scores legacy, Scores historical,
                   int historicalOnlyHits, int legacyOnlyHits,
                   List<SettledPrediction> legacyPredictions, List<SettledPrediction> historicalPredictions) {}
    record Fold(LocalDate start, LocalDate end, int trainingCount, LocalDate trainStart,
                LocalDate lastTrainingTarget, int validationCount, Scores majority, List<Compare> comparisons) {}

    /** 索引保留每条观测的来源版本有效区间，包括点号缺失，禁止跳过缺失再用更早的有效版本。 */
    static class History {
        final String series;
        final OffsetDateTime fetchedAt;
        final NavigableMap<LocalDate, List<Version>> byDate = new TreeMap<>(Comparator.reverseOrder());
        final String sha;
        final int count;
        History(Path path) throws Exception {
            byte[] bytes = Files.readAllBytes(path);
            sha = TrainingProbe.sha(bytes);
            JsonNode root = JSON.readTree(bytes);
            series = root.path("series").asText();
            fetchedAt = OffsetDateTime.parse(root.path("fetchedAt").asText());
            count = root.path("count").asInt();
            if (count != root.path("observations").size()
                    || !root.path("observationEnd").asText().equals("2024-11-08"))
                throw new IllegalArgumentException("版本缓存范围或数量不匹配");
            for (JsonNode row : root.path("observations")) {
                String text = row.path("value").asText();
                Version v = new Version(LocalDate.parse(row.path("date").asText()),
                        LocalDate.parse(row.path("realtime_start").asText()),
                        LocalDate.parse(row.path("realtime_end").asText()), text.equals(".") ? null : new BigDecimal(text));
                if (v.start().isAfter(v.end())) throw new IllegalArgumentException("版本区间反向");
                byDate.computeIfAbsent(v.date(), key -> new ArrayList<>()).add(v);
            }
            for (var versions : byDate.values()) {
                versions.sort(Comparator.comparing(Version::start));
                for (int i = 1; i < versions.size(); i++) {
                    if (!versions.get(i).start().isAfter(versions.get(i - 1).end()))
                        throw new IllegalStateException("来源版本区间重叠，不能猜测优先级");
                }
            }
        }

        List<MacroObservation> recent(LocalDate day, int limit) {
            // ALFRED 只有日级版本，没有日内发布时间；只接受前一完整日已知的版本。
            // 这不是把所有观测简单推迟一天：仍须逐条检查真实版本生效/失效区间。
            LocalDate cutoff = day.minusDays(1);
            List<MacroObservation> rows = new ArrayList<>();
            for (var entry : byDate.entrySet()) {
                if (entry.getKey().isAfter(cutoff)) continue;
                for (Version v : entry.getValue()) {
                    if (!cutoff.isBefore(v.start()) && !cutoff.isAfter(v.end()) && v.value() != null) {
                        String identity = series + v.date() + v.start() + v.value();
                        rows.add(new MacroObservation(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                                series, v.date(), v.value(), series.equals("DFII10") ? "percent" : "index",
                                "fred", fetchedAt, null));
                        break;
                    }
                }
                if (rows.size() == limit) break;
            }
            return rows;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}")) throw new IllegalArgumentException("需要输出文件和 Git 哈希");
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("不覆盖已有结果");
        Map<String, History> histories = new LinkedHashMap<>();
        for (String series : List.of("DFII10", "DTWEXBGS")) {
            History history = new History(Path.of("target/fred-history/" + series + ".json"));
            if (!series.equals(history.series)) throw new IllegalArgumentException("缓存序列不一致");
            histories.put(series, history);
        }
        checkHistory(histories);
        List<GoldDailyBar> bars = TrainingProbe.readBars();
        Map<String, List<MacroObservation>> current = readCurrent();
        GoldDailyBarRepository gold = (GoldDailyBarRepository) Proxy.newProxyInstance(
                VintageProbe.class.getClassLoader(), new Class<?>[]{GoldDailyBarRepository.class}, (proxy, method, params) -> {
                    if (method.getName().equals("findAll")) return bars;
                    if (method.getName().equals("findRecent") && params.length == 4) {
                        LocalDate end = (LocalDate) params[2];
                        return bars.stream().filter(b -> !b.priceDate().isAfter(end))
                                .sorted(Comparator.comparing(GoldDailyBar::priceDate).reversed()).limit((int) params[3]).toList();
                    }
                    throw new UnsupportedOperationException("研究适配器只允许受限日期的只读操作");
                });
        GoldDataset legacy = build(gold, macro(current, histories, false));
        GoldDataset historical = build(gold, macro(current, histories, true));
        Map<LocalDate, GoldSample> oldByDate = new HashMap<>();
        for (var s : legacy.samples()) oldByDate.put(s.asOfDate(), s);
        List<GoldSample> pairedOld = new ArrayList<>();
        for (var s : historical.samples()) {
            GoldSample old = oldByDate.get(s.asOfDate());
            if (old == null || old.label() != s.label() || !old.targetDate().equals(s.targetDate()))
                throw new IllegalStateException("配对样本日期或标签改变");
            for (String name : GoldOhlcFeatures.NAMES) {
                if (!old.features().values().get(name).equals(s.features().values().get(name)))
                    throw new IllegalStateException("时点修正不应改变 OHLC 特征");
            }
            pairedOld.add(old);
        }
        List<Fold> folds = new ArrayList<>();
        for (int i = 0; i < STARTS.size(); i++) {
            LocalDate start = STARTS.get(i), end = ENDS.get(i);
            List<GoldSample> train = training(historical.samples(), start);
            List<GoldSample> oldTrain = training(pairedOld, start);
            List<GoldSample> validation = validation(historical.samples(), start, end);
            List<GoldSample> oldValidation = validation(pairedOld, start, end);
            if (train.size() < 500 || train.size() != oldTrain.size() || validation.size() != 240 || oldValidation.size() != 240)
                throw new IllegalStateException("固定配对实验缺少样本");
            List<Compare> comparisons = new ArrayList<>();
            for (FeatureProfile profile : FeatureProfile.values()) {
                GoldClassifier a = new TribuoGoldTrainer(true).train(oldTrain, profile.featureNames());
                GoldClassifier b = new TribuoGoldTrainer(true).train(train, profile.featureNames());
                List<SettledPrediction> before = predict(a, oldValidation), after = predict(b, validation);
                int improved = 0, worsened = 0;
                for (int j = 0; j < before.size(); j++) {
                    int oldHit = hit(before.get(j)), newHit = hit(after.get(j));
                    improved += newHit > oldHit ? 1 : 0;
                    worsened += oldHit > newHit ? 1 : 0;
                    if (profile == FeatureProfile.OHLC_20 && !before.get(j).probabilities().equals(after.get(j).probabilities()))
                        throw new IllegalStateException("OHLC 阴性对照发生变化");
                }
                comparisons.add(new Compare(profile, score(before), score(after), improved, worsened, before, after));
                System.out.printf("%s %s legacy=%s historical=%s%n", start, profile,
                        comparisons.getLast().legacy().all().accuracy(), comparisons.getLast().historical().all().accuracy());
            }
            var base = new MajorityGoldTrainer().train(train);
            folds.add(new Fold(start, end, train.size(), train.getFirst().asOfDate(), train.getLast().targetDate(),
                    validation.size(), score(predict(base, validation)), comparisons));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("probeVersion", "fred-point-in-time-v1");
        report.put("createdAt", OffsetDateTime.now());
        report.put("gitCommit", args[1]);
        report.put("sourceSha256", TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/VintageProbe.java"))));
        report.put("barSha256", TrainingProbe.hashBars(bars));
        report.put("legacyCount", legacy.samples().size());
        report.put("historicalCount", historical.samples().size());
        report.put("historicalSkipped", historical.skippedCount());
        report.put("historicalFirst", historical.samples().getFirst().asOfDate());
        report.put("historicalLast", historical.samples().getLast().asOfDate());
        report.put("historicalHash", new GoldDatasetFingerprint().hash(historical));
        report.put("pairedLegacyHash", new GoldDatasetFingerprint().hash(new GoldDataset(pairedOld, historical.skippedCount())));
        Map<String, Object> sources = new LinkedHashMap<>();
        for (var h : histories.values()) sources.put(h.series, Map.of("count", h.count, "sha256", h.sha, "fetchedAt", h.fetchedAt));
        report.put("sources", sources);
        report.put("policy", "使用 asOf 前一完整日已知版本；不读取 2024-11-11 起的价格或标签；仅训练内诊断，无正式晋级");
        report.put("folds", folds);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    static void checkHistory(Map<String, History> histories) {
        History dollar = histories.get("DTWEXBGS");
        if (!dollar.recent(LocalDate.parse("2019-02-04"), 21).isEmpty())
            throw new IllegalStateException("不应在首次历史版本之前读到美元数据");
        var before = dollar.recent(LocalDate.parse("2024-11-02"), 21);
        if (before.isEmpty() || !before.getFirst().observationDate().isBefore(LocalDate.parse("2024-10-28")))
            throw new IllegalStateException("真实样例未阻止周发布滞后泄漏");
        var after = dollar.recent(LocalDate.parse("2024-11-05"), 21).getFirst();
        if (!after.observationDate().equals(LocalDate.parse("2024-11-01")) || after.value().compareTo(new BigDecimal("125.0403")) != 0)
            throw new IllegalStateException("真实已公布版本选择错误");
    }

    static GoldDataset build(GoldDailyBarRepository gold, MacroObservationRepository macro) {
        var snapshots = new GoldResearchSnapshotService(gold, macro, new RealRateFactorEvaluator(), new DollarIndexFactorEvaluator());
        return new GoldDatasetBuilder(gold, snapshots, new GoldForecastRule(), new GoldFeatureCalculator()).build(ForecastHorizon.NEXT_DAY);
    }

    static MacroObservationRepository macro(Map<String, List<MacroObservation>> current, Map<String, History> histories, boolean historical) {
        // 只读内存适配器喂给真实产品计算链；不 mock 行情，不重写 36 个特征公式。
        return (MacroObservationRepository) Proxy.newProxyInstance(VintageProbe.class.getClassLoader(), new Class<?>[]{MacroObservationRepository.class},
                (proxy, method, params) -> {
                    if (!method.getName().equals("findRecent") || params.length != 3) throw new UnsupportedOperationException("禁止写入或无截止日查询");
                    String series = (String) params[0]; LocalDate day = (LocalDate) params[1]; int limit = (int) params[2];
                    return historical ? histories.get(series).recent(day, limit) : current.get(series).stream()
                            .filter(o -> !o.observationDate().isAfter(day)).limit(limit).toList();
                });
    }

    static Map<String, List<MacroObservation>> readCurrent() throws Exception {
        Map<String, List<MacroObservation>> result = new HashMap<>();
        try (var conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/opspilot_ai", "postgres", System.getenv("OPSPILOT_DB_PASSWORD"))) {
            conn.setReadOnly(true); conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement("""
                    select id, series_id, observation_date, observation_value, unit, provider, collected_at
                    from macro_observation
                    where series_id in ('DFII10','DTWEXBGS') and observation_date < ? and superseded_at is null
                    order by observation_date desc
                    """)) {
                stmt.setObject(1, LIMIT);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String series = rs.getString(2);
                        result.computeIfAbsent(series, k -> new ArrayList<>()).add(new MacroObservation(rs.getObject(1, UUID.class),
                                series, rs.getObject(3, LocalDate.class), rs.getBigDecimal(4), rs.getString(5), rs.getString(6),
                                rs.getObject(7, OffsetDateTime.class), null));
                    }
                }
            }
            conn.rollback();
        }
        return result;
    }

    static List<GoldSample> training(List<GoldSample> rows, LocalDate start) {
        return rows.stream().filter(s -> s.asOfDate().isBefore(start) && s.targetDate().isBefore(start)).toList();
    }
    static List<GoldSample> validation(List<GoldSample> rows, LocalDate start, LocalDate end) {
        return rows.stream().filter(s -> !s.asOfDate().isBefore(start) && !s.asOfDate().isAfter(end)).toList();
    }
    static List<SettledPrediction> predict(GoldClassifier model, List<GoldSample> rows) {
        return rows.stream().map(s -> {
            DirectionProbabilities p = model.predict(s.features());
            ForecastDirection d = p.bullish() >= p.neutral() && p.bullish() >= p.bearish() ? ForecastDirection.BULLISH
                    : p.neutral() >= p.bearish() ? ForecastDirection.NEUTRAL : ForecastDirection.BEARISH;
            return new SettledPrediction(s.asOfDate(), p, new GoldPrediction(SignalStatus.PREDICTED, d,
                    Math.max(p.bullish(), Math.max(p.neutral(), p.bearish()))), s.label());
        }).toList();
    }
    static Scores score(List<SettledPrediction> rows) {
        var signals = rows.stream().map(r -> r.prediction().confidence() >= .55 ? r
                : new SettledPrediction(r.asOfDate(), r.probabilities(), new GoldPrediction(SignalStatus.NO_SIGNAL, null, r.prediction().confidence()), r.actual())).toList();
        var evaluator = new ForecastEvaluator();
        return new Scores(evaluator.evaluate(rows), evaluator.evaluate(signals));
    }
    static int hit(SettledPrediction row) { return row.prediction().direction() == row.actual() ? 1 : 0; }
}
