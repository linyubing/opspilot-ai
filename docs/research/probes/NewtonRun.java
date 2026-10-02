package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.GoldForecastRule;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** 固定真实数据的求解器诊断；只读训练期，不调整参数或发布模型。 */
public class NewtonRun {
    record Pair(LocalDate date, LocalDate target, String actual, double[] baseline, double[] reference, double[] prior) {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}")) throw new IllegalArgumentException("需要输出路径和完整 Git 哈希");
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("不覆盖已有诊断结果");
        NewtonProbeChecks.main(new String[0]);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var original = json.readTree(Path.of("../docs/research/2026-09-29-training-health.json").toFile());
        var bars = TrainingProbe.readBars();
        String barsHash = TrainingProbe.hashBars(bars);
        if (!barsHash.equals("68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0")) {
            throw new IllegalStateException("真实日线指纹变化，停止与旧训练诊断直接对照");
        }
        List<TrainingProbe.Row> rows = new ArrayList<>();
        var calculator = new GoldFeatureCalculator(); var rule = new GoldForecastRule();
        for (int i = 20; i + 1 < bars.size(); i++) {
            var base = bars.get(i); var target = bars.get(i + 1);
            var values = calculator.compute(base.priceDate(), bars.subList(i - 20, i + 1)).orElseThrow().values();
            double[] x = TrainingProbe.NAMES.stream().mapToDouble(n -> values.get(n)).toArray();
            var change = target.close().divide(base.close(), MathContext.DECIMAL128).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            rows.add(new TrainingProbe.Row(base.priceDate(), target.priceDate(), x, rule.classify(change)));
        }
        if (rows.size() != 4361) throw new IllegalStateException("固定样本数变化");
        List<Map<String, Object>> folds = new ArrayList<>(); int foldId = 0;
        for (int start = rows.size() - 720; start < rows.size(); start += 240, foldId++) {
            var validation = rows.subList(start, start + 240);
            LocalDate day = validation.getFirst().date();
            var train = rows.subList(0, start).stream().filter(r -> r.target().isBefore(day)).toList();
            if (!train.getLast().target().isBefore(day) || !validation.getLast().target().isBefore(TrainingProbe.LIMIT)) {
                throw new IllegalStateException("时间隔离失败");
            }
            var scale = new TrainingProbe.Scale(train);
            double[][] x = train.stream().map(scale::values).toArray(double[][]::new);
            int[] y = train.stream().mapToInt(r -> r.label().ordinal()).toArray();
            // 只以训练矩阵求解；固定 100 次上限及 1e-6 梯度，不用验证分数决定何时停止。
            var fit = NewtonProbe.fit(x, y, 100);
            double[][] design = new double[x.length][21];
            for (int i = 0; i < x.length; i++) { System.arraycopy(x[i], 0, design[i], 0, 20); design[i][20] = 1; }
            var svd = new SingularValueDecomposition(new Array2DRowRealMatrix(design, false));
            Map<String, Object> fold = new LinkedHashMap<>();
            fold.put("start", day); fold.put("end", validation.getLast().date()); fold.put("trainCount", train.size());
            fold.put("trainTargetEnd", train.getLast().target()); fold.put("fit", fit);
            fold.put("designRank", svd.getRank()); fold.put("designSingularValues", svd.getSingularValues());
            folds.add(fold);
            if (!fit.converged()) {
                fold.put("predictions", List.of());
                System.out.println(day + " NOT_CONVERGED " + fit.status());
                continue;
            }
            var model = TrainingProbe.trainer(5).train(TrainingProbe.dataset(train, scale));
            Function<TrainingProbe.Row, double[]> baseline = r -> TrainingProbe.probabilities(model, r, scale);
            Function<TrainingProbe.Row, double[]> reference = r -> NewtonProbe.probabilities(fit.weights(), scale.values(r));
            double[] prior = new double[3];
            for (var row : train) prior[row.label().ordinal()] += 1.0 / train.size();
            double delta = 0;
            var oldPredictions = original.path("folds").get(foldId).path("checks").get(0).path("predictions");
            for (int i = 0; i < validation.size(); i++) {
                var row = validation.get(i); var old = oldPredictions.get(i);
                if (!row.date().toString().equals(old.path("date").asText())) throw new IllegalStateException("旧预测日期不一致");
                double[] p = baseline.apply(row);
                for (int c = 0; c < 3; c++) delta = Math.max(delta, Math.abs(p[c] - old.path("p").get(c).asDouble()));
            }
            if (delta > 1e-12) throw new IllegalStateException("五轮基线与旧研究未完全复现");
            fold.put("baselineMaxDifference", delta);
            fold.put("baselineTrain", TrainingProbe.score(train, baseline, scale));
            fold.put("referenceTrain", TrainingProbe.score(train, reference, scale));
            fold.put("baselineValidation", TrainingProbe.score(validation, baseline, scale));
            fold.put("referenceValidation", TrainingProbe.score(validation, reference, scale));
            fold.put("priorValidation", TrainingProbe.score(validation, r -> prior, scale));
            var selected = validation.stream().filter(r -> Arrays.stream(reference.apply(r)).max().orElseThrow() >= .55).toList();
            fold.put("referenceSelectedDates", selected.stream().map(TrainingProbe.Row::date).toList());
            fold.put("priorOnReferenceSignals", selected.isEmpty() ? null : TrainingProbe.score(selected, r -> prior, scale).all());
            fold.put("predictions", validation.stream().map(r -> new Pair(r.date(), r.target(), r.label().name(), baseline.apply(r), reference.apply(r), prior)).toList());
            System.out.println(day + " CONVERGED iterations=" + fit.trace().getLast().iteration()
                    + " gradient=" + fit.trace().getLast().gradient() + " rank=" + svd.getRank());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("probeVersion", "ohlc-newton-reference-v1"); result.put("gitCommit", args[1]); result.put("createdAt", OffsetDateTime.now());
        result.put("barsSha256", barsHash); result.put("barCount", bars.size()); result.put("samples", rows.size());
        result.put("cutoffExclusive", TrainingProbe.LIMIT); result.put("featureNames", TrainingProbe.NAMES);
        result.put("ruleVersion", GoldForecastRule.RULE_VERSION); result.put("promotionAllowed", false);
        result.put("method", "无正则三分类交叉熵；固定参考类消除平移冗余；Newton-SVD，100次上限，完整三类梯度max<=1e-6，Armijo=1e-4，最多50次二分回溯；不搜索参数");
        result.put("scope", "只用既定初始训练期三段240条诊断；OHLC_20不使用宏观值；日线并非历史修订版本；不代表正式模型或未来结果");
        Map<String,String> hashes = new LinkedHashMap<>();
        for (String file : List.of("NewtonProbe.java", "NewtonProbeChecks.java", "NewtonRun.java", "TrainingProbe.java")) {
            hashes.put(file, TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/" + file))));
        }
        result.put("sourceHashes", hashes); result.put("folds", folds);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.println("REPORT " + output + " allConverged=" + folds.stream().allMatch(f -> ((NewtonProbe.Fit) f.get("fit")).converged()));
    }
}
