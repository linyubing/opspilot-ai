package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.analysis.*;
import com.opspilot.ai.forecast.ConfiguredGoldTradingCalendar;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.forecast.learning.*;
import com.opspilot.ai.macrodata.FredHistoryStore;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;

/** 只读审计旧初始训练期的当前可用样本，不训练、不读取开发集或留出集。 */
public class DatasetReadiness {
    private static final LocalDate LIMIT = LocalDate.parse("2024-11-11");

    public static void main(String[] args) throws Exception {
        if ((args.length != 2 && args.length != 3) || !args[1].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("需要新输出路径及完整提交哈希");
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("禁止覆盖已有审计");
        String password = System.getenv("OPSPILOT_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("数据库密码未配置");
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var bars = new ArrayList<GoldDailyBar>();
        // SQL 在取数前硬截断；不要为了“最近行情”读入封闭的外层评估期。
        try (var connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/opspilot_ai", "postgres", password)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (var statement = connection.prepareStatement("""
                    select symbol, price_date, open_price, high_price, low_price, close_price,
                           currency, unit, provider, collected_at,
                           closed_day, confirmed_at, confirmation_source, confirmation_hash
                    from gold_daily_bar
                    where symbol = 'XAUUSD' and provider = 'twelve_data' and price_date < ?
                    order by price_date
                    """)) {
                statement.setObject(1, LIMIT);
                try (var row = statement.executeQuery()) {
                    while (row.next()) {
                        var proof = row.getObject(12, OffsetDateTime.class) == null ? null :
                                new GoldBarConfirmation(row.getString(13), row.getObject(11, LocalDate.class),
                                        row.getObject(12, OffsetDateTime.class), row.getString(14));
                        bars.add(new GoldDailyBar(row.getString(1), row.getObject(2, LocalDate.class),
                                row.getBigDecimal(3), row.getBigDecimal(4), row.getBigDecimal(5), row.getBigDecimal(6),
                                row.getString(7), row.getString(8), row.getString(9), row.getObject(10, OffsetDateTime.class), proof));
                    }
                }
            }
            connection.rollback();
        }
        if (bars.isEmpty() || !bars.getLast().priceDate().isBefore(LIMIT))
            throw new IllegalStateException("只读范围校验失败");
        var source = new org.springframework.boot.env.YamlPropertySourceLoader().load("readiness-calendar",
                new org.springframework.core.io.ClassPathResource("application.yaml")).getFirst();
        Set<String> holidays = new HashSet<>();
        for (int i = 0; source.getProperty("opspilot.forecast.gold.holidays[" + i + "]") != null; i++)
            holidays.add(source.getProperty("opspilot.forecast.gold.holidays[" + i + "]").toString());
        var calendar = new ConfiguredGoldTradingCalendar();
        calendar.setHolidays(holidays);
        var window = new GoldBarWindow(calendar);
        GoldDailyBarRepository repository = (GoldDailyBarRepository) Proxy.newProxyInstance(
                DatasetReadiness.class.getClassLoader(), new Class<?>[]{GoldDailyBarRepository.class}, (proxy, method, params) -> {
                    if (method.getName().equals("findAll") && params.length == 2) return List.copyOf(bars);
                    throw new IllegalArgumentException("禁止额外取数或写入");
                });
        MacroObservationRepository macro = (MacroObservationRepository) Proxy.newProxyInstance(
                DatasetReadiness.class.getClassLoader(), new Class<?>[]{MacroObservationRepository.class}, (proxy, method, params) -> {
                    throw new IllegalArgumentException("禁止读取最新宏观库或写入");
                });
        var clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
        var snapshots = new GoldResearchSnapshotService(repository, macro,
                new RealRateFactorEvaluator(), new DollarIndexFactorEvaluator(), clock, window);
        var builder = new GoldDatasetBuilder(repository, snapshots, new GoldForecastRule(),
                new GoldFeatureCalculator(), new FredHistoryStore(json, System.getenv("FRED_HISTORY_DIR")), clock, window);
        int rejectedConfirmation = 0, rejectedContinuity = 0, eligible = 0;
        Map<String, Integer> skippedMonths = new TreeMap<>();
        for (int i = 20; i + 1 < bars.size(); i++) {
            var group = bars.subList(i - 20, i + 2);
            boolean confirmed = group.stream().allMatch(bar -> bar.isConfirmedAt(now));
            boolean continuous = window.isContinuous(group);
            if (!confirmed) rejectedConfirmation++;
            if (!continuous) rejectedContinuity++;
            if (confirmed && continuous) eligible++;
            else skippedMonths.merge(YearMonth.from(bars.get(i).priceDate()).toString(), 1, Integer::sum);
        }
        // 真正复用生产构建器；只在已硬截断的初始训练期内检查特征可构建性。
        var dataset = builder.build(ForecastHorizon.NEXT_DAY);
        // 可选导出只保存已截断的真实样本，供预登记的离线实验使用；不训练模型。
        if (args.length == 3) Files.writeString(Path.of(args[2]),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(dataset) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", "gold-training-readiness-v1"); report.put("gitCommit", args[1]);
        report.put("checkedAt", now); report.put("cutoffExclusive", LIMIT);
        report.put("promotionAllowed", false); report.put("holdoutRead", false);
        report.put("barCount", bars.size()); report.put("barStart", bars.getFirst().priceDate());
        report.put("barEnd", bars.getLast().priceDate()); report.put("candidateWindows", bars.size() - 21);
        report.put("unconfirmedWindows", rejectedConfirmation); report.put("discontinuousWindows", rejectedContinuity);
        report.put("bothGoldGatesPassed", eligible); report.put("sampleCount", dataset.samples().size());
        report.put("skippedCount", dataset.skippedCount()); report.put("skippedGoldMonths", skippedMonths);
        report.put("historyMetadata", dataset.macroInput()); report.put("holidays", new TreeSet<>(holidays));
        report.put("calendarSource", "compiled-application-yaml-not-runtime-overrides");
        report.put("datasetHash", new GoldDatasetFingerprint().hash(dataset));
        report.put("scope", "当前可核验的历史训练数据；闭市确认不等于历史当时可得证明；无模型运行或评分");
        if (!dataset.samples().isEmpty()) {
            report.put("sampleStart", dataset.samples().getFirst().asOfDate());
            report.put("sampleEnd", dataset.samples().getLast().asOfDate());
        }
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("READINESS_EXPORTED; SAMPLES=" + dataset.samples().size() + "; NO_MODEL_RUN; NO_DATABASE_WRITE");
    }
}
