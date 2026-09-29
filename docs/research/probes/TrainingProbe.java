package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.GoldDailyBar;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.Trainer;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.classification.sgd.linear.LinearSGDTrainer;
import org.tribuo.classification.sgd.linear.LogisticRegressionTrainer;
import org.tribuo.classification.sgd.objectives.LogMulticlass;
import org.tribuo.impl.ArrayExample;
import org.tribuo.math.optimisers.AdaGrad;
import org.tribuo.provenance.SimpleDataSourceProvenance;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** 一次性训练体检探针；不接入产品、不写数据库、不选择正式模型。 */
public class TrainingProbe {
    // 固定旧开发集起点；SQL 也排除这一天及之后，避免读取外层开发集和最终留出集。
    static final LocalDate LIMIT = LocalDate.parse("2024-11-11");
    static final List<Integer> EPOCHS = List.of(5, 20, 80);
    static final List<String> NAMES = GoldOhlcFeatures.NAMES.stream().sorted().toList();
    static final String[] LABELS = {"BULLISH", "NEUTRAL", "BEARISH"};
    static final int BLOCK = 240;

    /** 诊断样本只保存真实的 20 项特征，不为缺失宏观特征填零。 */
    record Row(LocalDate date, LocalDate target, double[] x, ForecastDirection label) {}
    record PredictionRow(LocalDate date, LocalDate target, ForecastDirection actual, double[] p) {}
    record Score(ForecastMetrics all, ForecastMetrics signals, double gradientNorm) {}
    record Check(int epochs, Score train, Score validation, List<PredictionRow> predictions) {}
    record Fold(LocalDate start, LocalDate end, LocalDate trainEnd, LocalDate trainTargetEnd,
                int trainCount, int purged, Map<String, Integer> trainLabels,
                Map<String, Integer> validationLabels, Score prior, List<Check> checks,
                double defaultMaxDifference, double repeatMaxDifference) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("参数：输出 JSON 文件、完整 Git 哈希");
        }
        if (Files.exists(Path.of(args[0]))) throw new IllegalArgumentException("不覆盖已有诊断结果");
        List<GoldDailyBar> bars = readBars();
        GoldFeatureCalculator calculator = new GoldFeatureCalculator();
        GoldForecastRule rule = new GoldForecastRule();
        List<Row> rows = new ArrayList<>();
        for (int i = 20; i + 1 < bars.size(); i++) {
            GoldDailyBar base = bars.get(i), target = bars.get(i + 1);
            var features = calculator.compute(base.priceDate(), bars.subList(i - 20, i + 1))
                    .orElseThrow();
            double[] x = NAMES.stream().mapToDouble(n -> features.values().get(n)).toArray();
            BigDecimal change = target.close().divide(base.close(), MathContext.DECIMAL128)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            rows.add(new Row(base.priceDate(), target.priceDate(), x, rule.classify(change)));
        }
        if (rows.size() != 4361) throw new IllegalStateException("固定训练期样本数变化，停止诊断");
        List<Fold> folds = new ArrayList<>();
        for (int start = rows.size() - 3 * BLOCK; start < rows.size(); start += BLOCK) {
            List<Row> validation = rows.subList(start, start + BLOCK);
            LocalDate first = validation.getFirst().date();
            List<Row> train = rows.subList(0, start).stream().filter(r -> r.target().isBefore(first)).toList();
            if (train.size() < 500 || validation.getLast().target().compareTo(LIMIT) >= 0)
                throw new IllegalStateException("诊断时间隔离失败");
            Scale scale = new Scale(train);
            // 两种构造器的五轮输出必须一致，保证观察到的是轮数变化，不是换了训练算法。
            Model<Label> original = new LogisticRegressionTrainer().train(dataset(train, scale));
            List<Check> checks = new ArrayList<>();
            double diff = 0, repeatDiff = 0;
            for (int epochs : EPOCHS) {
                Model<Label> model = trainer(epochs).train(dataset(train, scale));
                Function<Row, double[]> predict = row -> probabilities(model, row, scale);
                if (epochs == 5) {
                    Model<Label> repeat = trainer(epochs).train(dataset(train, scale));
                    for (Row row : validation) {
                        diff = Math.max(diff, distance(predict.apply(row), probabilities(original, row, scale)));
                        repeatDiff = Math.max(repeatDiff, distance(predict.apply(row), probabilities(repeat, row, scale)));
                    }
                    if (diff > 1e-12 || repeatDiff > 1e-12)
                        throw new IllegalStateException("默认参数对齐或固定种子复现失败");
                }
                checks.add(new Check(epochs, score(train, predict, scale), score(validation, predict, scale),
                        validation.stream().map(r -> new PredictionRow(r.date(), r.target(), r.label(), predict.apply(r))).toList()));
                System.out.printf(Locale.ROOT, "%s epochs=%d trainLoss=%s valLoss=%s valAcc=%s%n",
                        first, epochs, checks.getLast().train().all().logLoss(),
                        checks.getLast().validation().all().logLoss(), checks.getLast().validation().all().accuracy());
            }
            // 只由当前训练期类别频率决定概率；最大概率方向就是训练期多数类。
            double[] prior = new double[3];
            for (Row row : train) prior[row.label().ordinal()] += 1.0 / train.size();
            folds.add(new Fold(first, validation.getLast().date(), train.getLast().date(),
                    train.getLast().target(), train.size(), start - train.size(), counts(train), counts(validation),
                    score(validation, r -> prior, scale), checks, diff, repeatDiff));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("probeVersion", "ohlc-training-health-v1");
        report.put("gitCommit", args[1]);
        report.put("createdAt", OffsetDateTime.now());
        report.put("sourceSha256", sha(Files.readAllBytes(Path.of("../docs/research/probes/TrainingProbe.java"))));
        report.put("barsSha256", hashBars(bars));
        report.put("barCount", bars.size());
        report.put("barStart", bars.getFirst().priceDate());
        report.put("barEnd", bars.getLast().priceDate());
        report.put("sampleCount", rows.size());
        report.put("labels", counts(rows));
        report.put("featureNames", NAMES);
        report.put("featureVersion", GoldOhlcFeatures.VERSION);
        report.put("ruleVersion", GoldForecastRule.RULE_VERSION);
        report.put("cutoffExclusive", LIMIT);
        report.put("optimizer", "AdaGrad(1.0,0.1), seed=12345, batch=1, no weights, no regularization");
        report.put("scoring", "完整样本最大概率方向；signals 单列 0.55 门槛，概率误差仍覆盖全部样本");
        report.put("scope", "仅初始训练期内部诊断；未读取旧开发集或最终留出集；不证明未来准确率或日线历史修订可得性");
        report.put("folds", folds);
        new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    }

    static LinearSGDTrainer trainer(int epochs) {
        return new LinearSGDTrainer(new LogMulticlass(), new AdaGrad(1.0, 0.1), epochs, Trainer.DEFAULT_SEED);
    }

    static List<GoldDailyBar> readBars() throws Exception {
        String password = System.getenv("OPSPILOT_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("数据库密码环境变量缺失");
        List<GoldDailyBar> bars = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/opspilot_ai", "postgres", password)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    select symbol, price_date, open_price, high_price, low_price, close_price,
                           currency, unit, provider, collected_at
                    from gold_daily_bar
                    where symbol = 'XAUUSD' and provider = 'twelve_data' and price_date < ?
                    order by price_date
                    """)) {
                statement.setObject(1, LIMIT);
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) bars.add(new GoldDailyBar(rs.getString(1), rs.getObject(2, LocalDate.class),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                            rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class)));
                }
            }
            connection.rollback();
        }
        for (int i = 0; i < bars.size(); i++) {
            var b = bars.get(i);
            if (b.close().signum() <= 0 || b.open().signum() <= 0 || b.low().signum() <= 0
                    || b.high().compareTo(b.low()) < 0 || b.high().compareTo(b.close()) < 0
                    || b.high().compareTo(b.open()) < 0 || b.low().compareTo(b.close()) > 0
                    || b.low().compareTo(b.open()) > 0 || (i > 0 && !b.priceDate().isAfter(bars.get(i - 1).priceDate())))
                throw new IllegalStateException("日线完整性校验失败：" + b.priceDate());
        }
        return bars;
    }

    /** 在当前训练折内拟合 Welford 均值和总体标准差，与产品 FeatureScaler 的算法一致。 */
    static class Scale {
        final double[] mean = new double[NAMES.size()], std = new double[NAMES.size()];
        Scale(List<Row> rows) {
            int count = 0;
            for (Row row : rows) {
                count++;
                for (int j = 0; j < mean.length; j++) {
                    double delta = row.x()[j] - mean[j];
                    mean[j] += delta / count;
                    std[j] += delta * (row.x()[j] - mean[j]);
                }
            }
            for (int j = 0; j < mean.length; j++) std[j] = Math.sqrt(Math.max(0, std[j] / count));
        }
        double[] values(Row row) {
            double[] x = new double[mean.length];
            for (int j = 0; j < x.length; j++) x[j] = std[j] == 0 ? 0 : (row.x()[j] - mean[j]) / std[j];
            return x;
        }
    }

    static MutableDataset<Label> dataset(List<Row> rows, Scale scale) {
        LabelFactory factory = new LabelFactory();
        var data = new MutableDataset<Label>(new SimpleDataSourceProvenance("training-health-probe", factory), factory);
        for (Row row : rows) data.add(new ArrayExample<>(new Label(row.label().name()), NAMES.toArray(String[]::new), scale.values(row)));
        return data;
    }

    static double[] probabilities(Model<Label> model, Row row, Scale scale) {
        var prediction = model.predict(new ArrayExample<>(LabelFactory.UNKNOWN_LABEL, NAMES.toArray(String[]::new), scale.values(row)));
        double[] p = Arrays.stream(LABELS).mapToDouble(n -> prediction.getOutputScores().get(n).getScore()).toArray();
        if (!prediction.hasProbabilities() || Arrays.stream(p).anyMatch(v -> !Double.isFinite(v) || v < 0 || v > 1)
                || Math.abs(Arrays.stream(p).sum() - 1) > 1e-10) throw new IllegalStateException("概率非法");
        return p;
    }

    static Score score(List<Row> rows, Function<Row, double[]> predict, Scale scale) {
        List<SettledPrediction> all = new ArrayList<>(), signals = new ArrayList<>();
        double[][] gradient = new double[3][NAMES.size() + 1];
        for (Row row : rows) {
            double[] p = predict.apply(row), x = scale.values(row);
            int best = p[0] >= p[1] && p[0] >= p[2] ? 0 : p[1] >= p[2] ? 1 : 2;
            var probs = new DirectionProbabilities(p[0], p[1], p[2]);
            var direction = ForecastDirection.valueOf(LABELS[best]);
            all.add(new SettledPrediction(row.date(), probs, new GoldPrediction(SignalStatus.PREDICTED, direction, p[best]), row.label()));
            var status = p[best] >= .55 ? SignalStatus.PREDICTED : SignalStatus.NO_SIGNAL;
            signals.add(new SettledPrediction(row.date(), probs, new GoldPrediction(status, status == SignalStatus.NO_SIGNAL ? null : direction, p[best]), row.label()));
            // 未正则化交叉熵的平均梯度范数，仅作为训练充分性指标，不用于挑验证期最优点。
            for (int c = 0; c < 3; c++) {
                double error = p[c] - (row.label().ordinal() == c ? 1 : 0);
                for (int j = 0; j < x.length; j++) gradient[c][j] += error * x[j] / rows.size();
                gradient[c][x.length] += error / rows.size();
            }
        }
        double norm = 0;
        for (double[] g : gradient) for (double v : g) norm += v * v;
        ForecastEvaluator evaluator = new ForecastEvaluator();
        return new Score(evaluator.evaluate(all), evaluator.evaluate(signals), Math.sqrt(norm));
    }

    static Map<String, Integer> counts(List<Row> rows) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String label : LABELS) result.put(label, 0);
        for (Row row : rows) result.merge(row.label().name(), 1, Integer::sum);
        return result;
    }
    static double distance(double[] a, double[] b) {
        double max = 0;
        for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }
    static String hashBars(List<GoldDailyBar> bars) throws Exception {
        StringBuilder text = new StringBuilder();
        for (var b : bars) text.append(b.priceDate()).append('|').append(b.open().toPlainString()).append('|')
                .append(b.high().toPlainString()).append('|').append(b.low().toPlainString()).append('|')
                .append(b.close().toPlainString()).append('\n');
        return sha(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    static String sha(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
