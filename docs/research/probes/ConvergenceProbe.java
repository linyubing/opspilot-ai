package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.GoldForecastRule;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunctionGradient;
import org.apache.commons.math3.optim.nonlinear.scalar.gradient.NonLinearConjugateGradientOptimizer;
import org.apache.commons.math3.exception.TooManyIterationsException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** 一次性收敛参照；仍是无正则化三分类逻辑回归，只替换求解器来隔离优化误差。 */
public class ConvergenceProbe {
    static final int WIDTH = 21; // 20 项真实 OHLC 特征 + 截距，不增加市场信息。
    static final double TOL = 1e-6;
    record Step(int iteration, double loss, double gradientMax) {}
    record Fit(boolean converged, String status, double[] weights, List<Step> trace, int evaluations, double gradientError) {}
    record Fold(LocalDate start, LocalDate end, int trainingCount, LocalDate lastTrainingTarget,
                TrainingProbe.Score baselineTrain, TrainingProbe.Score baselineValidation,
                TrainingProbe.Score referenceTrain, TrainingProbe.Score referenceValidation,
                List<TrainingProbe.PredictionRow> predictions, Fit fit) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}")) throw new IllegalArgumentException("需要输出文件和 Git 哈希");
        if (Files.exists(Path.of(args[0]))) throw new IllegalArgumentException("不覆盖已有结果");
        var bars = TrainingProbe.readBars();
        var calculator = new GoldFeatureCalculator();
        var rule = new GoldForecastRule();
        List<TrainingProbe.Row> rows = new ArrayList<>();
        for (int i = 20; i + 1 < bars.size(); i++) {
            var base = bars.get(i); var target = bars.get(i + 1);
            var features = calculator.compute(base.priceDate(), bars.subList(i - 20, i + 1)).orElseThrow();
            double[] x = TrainingProbe.NAMES.stream().mapToDouble(n -> features.values().get(n)).toArray();
            var change = target.close().divide(base.close(), MathContext.DECIMAL128).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            rows.add(new TrainingProbe.Row(base.priceDate(), target.priceDate(), x, rule.classify(change)));
        }
        if (rows.size() != 4361) throw new IllegalStateException("固定训练样本变化");
        List<Fold> folds = new ArrayList<>();
        for (int start = rows.size() - 720; start < rows.size(); start += 240) {
            var validation = rows.subList(start, start + 240);
            LocalDate first = validation.getFirst().date();
            var training = rows.subList(0, start).stream().filter(r -> r.target().isBefore(first)).toList();
            var scale = new TrainingProbe.Scale(training);
            var baseline = TrainingProbe.trainer(5).train(TrainingProbe.dataset(training, scale));
            Function<TrainingProbe.Row, double[]> old = r -> TrainingProbe.probabilities(baseline, r, scale);
            // 优化器只能得到训练矩阵和标签，既不接收验证行，也不接收验证得分。
            double[][] x = training.stream().map(scale::values).toArray(double[][]::new);
            int[] y = training.stream().mapToInt(r -> r.label().ordinal()).toArray();
            Objective objective = new Objective(x, y);
            Fit fit = fit(objective);
            if (!fit.converged()) {
                // 没有收敛就不查看这个参照的验证分数，避免借故放宽标准或挑选参数。
                folds.add(new Fold(first, validation.getLast().date(), training.size(), training.getLast().target(),
                        TrainingProbe.score(training, old, scale), null, null, null, List.of(), fit));
                System.out.printf("%s NOT_CONVERGED gradient=%g status=%s%n", first,
                        fit.trace().getLast().gradientMax(), fit.status());
                continue;
            }
            Function<TrainingProbe.Row, double[]> predict = r -> probabilities(fit.weights(), scale.values(r));
            folds.add(new Fold(first, validation.getLast().date(), training.size(), training.getLast().target(),
                    TrainingProbe.score(training, old, scale), TrainingProbe.score(validation, old, scale),
                    TrainingProbe.score(training, predict, scale), TrainingProbe.score(validation, predict, scale),
                    validation.stream().map(r -> new TrainingProbe.PredictionRow(r.date(), r.target(), r.label(), predict.apply(r))).toList(), fit));
            System.out.printf("%s iterations=%d gradient=%g trainLoss=%s validationAcc=%s%n", first,
                    fit.trace().getLast().iteration(), fit.trace().getLast().gradientMax(),
                    folds.getLast().referenceTrain().all().logLoss(), folds.getLast().referenceValidation().all().accuracy());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("probeVersion", "ohlc-convergence-reference-v1"); report.put("gitCommit", args[1]);
        report.put("createdAt", OffsetDateTime.now());
        report.put("sourceSha256", TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/ConvergenceProbe.java"))));
        report.put("barsSha256", TrainingProbe.hashBars(bars));
        report.put("optimizer", "Commons Math 3.6.1 Polak-Ribiere, maxIter=2000, maxEval=50000, gradient infinity norm <=1e-6, line search tolerances=1e-10, initial range=1");
        report.put("scope", "无正则化、无权重、同一 20 项特征；只用原训练期内三段 240 条，不改变正式模型");
        report.put("folds", folds);
        VintageProbe.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    }

    static Fit fit(Objective f) {
        double error = gradientCheck(f);
        List<Step> trace = new ArrayList<>();
        var optimizer = new NonLinearConjugateGradientOptimizer(
                NonLinearConjugateGradientOptimizer.Formula.POLAK_RIBIERE,
                (iteration, previous, current) -> {
                    double max = max(f.gradient(current.getPointRef()));
                    trace.add(new Step(iteration, current.getValue(), max));
                    return max <= TOL;
                }, 1e-10, 1e-10, 1.0);
        // 停止条件预先固定，达不到则报错，不能看验证准确率后放宽“收敛”标准。
        try {
            var result = optimizer.optimize(new MaxEval(50000), new MaxIter(2000), GoalType.MINIMIZE,
                    new InitialGuess(new double[3 * WIDTH]), new ObjectiveFunction(f::loss),
                    new ObjectiveFunctionGradient(f::gradient));
            if (max(f.gradient(result.getPointRef())) > TOL) throw new IllegalStateException("参照解未达到预定梯度标准");
            return new Fit(true, "CONVERGED", result.getPoint(), trace, optimizer.getEvaluations(), error);
        } catch (TooManyIterationsException | TooManyEvaluationsException limit) {
            return new Fit(false, limit.getClass().getSimpleName(), null, trace, optimizer.getEvaluations(), error);
        }
    }

    /** 稳定 softmax：先减最大 logit，避免指数溢出。 */
    static double[] probabilities(double[] w, double[] x) {
        double[] p = logits(w, x);
        double max = Arrays.stream(p).max().orElseThrow(), sum = 0;
        for (int c = 0; c < 3; c++) { p[c] = Math.exp(p[c] - max); sum += p[c]; }
        for (int c = 0; c < 3; c++) p[c] /= sum;
        return p;
    }
    static double[] logits(double[] w, double[] x) {
        double[] result = new double[3];
        for (int c = 0; c < 3; c++) {
            result[c] = w[c * WIDTH + WIDTH - 1];
            for (int j = 0; j < WIDTH - 1; j++) result[c] += w[c * WIDTH + j] * x[j];
        }
        return result;
    }

    /** 平均交叉熵与解析梯度；不加惩罚项，避免把求解器和正则化混为一个改动。 */
    static class Objective {
        final double[][] x; final int[] y;
        Objective(double[][] x, int[] y) { this.x = x; this.y = y; }
        double loss(double[] w) {
            double total = 0;
            for (int i = 0; i < x.length; i++) {
                double[] z = logits(w, x[i]);
                double m = Math.max(z[0], Math.max(z[1], z[2]));
                total += m + Math.log(Math.exp(z[0] - m) + Math.exp(z[1] - m) + Math.exp(z[2] - m)) - z[y[i]];
            }
            return total / x.length;
        }
        double[] gradient(double[] w) {
            double[] g = new double[w.length];
            for (int i = 0; i < x.length; i++) {
                double[] p = probabilities(w, x[i]);
                for (int c = 0; c < 3; c++) {
                    double error = (p[c] - (y[i] == c ? 1 : 0)) / x.length;
                    for (int j = 0; j < WIDTH - 1; j++) g[c * WIDTH + j] += error * x[i][j];
                    g[c * WIDTH + WIDTH - 1] += error;
                }
            }
            return g;
        }
    }

    static double gradientCheck(Objective f) {
        double[] w = new double[3 * WIDTH];
        for (int i = 0; i < w.length; i++) w[i] = .01 * Math.sin(i);
        double[] g = f.gradient(w);
        double worst = 0, step = 1e-5;
        // 所有 63 个坐标用中心差分校验，防止手写目标函数的梯度符号或截距出错。
        for (int i = 0; i < w.length; i++) {
            double old = w[i]; w[i] = old + step; double plus = f.loss(w);
            w[i] = old - step; double minus = f.loss(w); w[i] = old;
            worst = Math.max(worst, Math.abs((plus - minus) / (2 * step) - g[i]));
        }
        if (worst > 1e-6) throw new IllegalStateException("解析梯度与数值差分不一致");
        return worst;
    }
    static double max(double[] values) { return Arrays.stream(values).map(Math::abs).max().orElseThrow(); }
}
