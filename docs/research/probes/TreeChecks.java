package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.tribuo.common.xgboost.XGBoostModel;

/** 人工数学夹具检查树适配；不构造或宣称任何黄金行情。 */
public class TreeChecks {
    public static void main(String[] args) throws Exception {
        List<TrainingProbe.Row> rows = new ArrayList<>();
        for (int repeat = 0; repeat < 5; repeat++) for (int a = -4; a <= 4; a++) for (int b = -4; b <= 4; b++) {
            var label = Math.abs(a) <= 1 && Math.abs(b) <= 1 ? ForecastDirection.NEUTRAL
                    : a * b >= 0 ? ForecastDirection.BULLISH : ForecastDirection.BEARISH;
            rows.add(row(rows.size(), a / 2.0, b / 2.0, label));
        }
        var scale = new TrainingProbe.Scale(rows); var first = TreeFit.train(rows, scale);
        // 手工确定的四象限与中心；线性边界无法同时学会四象限。
        List<TrainingProbe.Row> points = List.of(row(0, 2, 2, ForecastDirection.BULLISH),
                row(1, -2, -2, ForecastDirection.BULLISH), row(2, -2, 2, ForecastDirection.BEARISH),
                row(3, 2, -2, ForecastDirection.BEARISH), row(4, 0, 0, ForecastDirection.NEUTRAL));
        for (var point : points) {
            double[] p = TreeFit.probabilities(first, point, scale);
            if (p[point.label().ordinal()] <= .60) throw new AssertionError("非线性已知点未识别：" + point.label() + " " + java.util.Arrays.toString(p));
        }
        var second = TreeFit.train(rows, scale);
        if (first == second) throw new AssertionError("重复训练共用模型实例");
        for (var point : points) if (TrainingProbe.distance(TreeFit.probabilities(first, point, scale),
                TreeFit.probabilities(second, point, scale)) > 1e-12) throw new AssertionError("固定种子概率不一致");
        rejects(() -> TreeFit.train(List.of(), scale));
        rejects(() -> TreeFit.train(List.of(new TrainingProbe.Row(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2), new double[19], ForecastDirection.BULLISH)), scale));
        var bad = row(0, 1, 1, ForecastDirection.BULLISH); bad.x()[3] = Double.NaN;
        rejects(() -> TreeFit.train(List.of(bad), scale));
        if (!(first instanceof XGBoostModel<?> model)) throw new AssertionError("未使用XGBoost");
        var copies = model.getInnerModels();
        try {
            if (copies.size() != 1) throw new AssertionError("原生模型数量不为1");
            var json = new ObjectMapper().readTree(copies.getFirst().toByteArray("json"));
            var trees = json.path("learner").path("gradient_booster").path("model").path("trees");
            if (trees.size() != 600) throw new AssertionError("200轮三分类应生成600棵树");
            Files.writeString(Path.of("target/tree-fixture-model.json"), json.toString());
        } finally { for (var copy : copies) copy.dispose(); }
        System.out.println("PASS: 数学非线性/独立20维训练/概率/固定种子/独立实例/非法输入/模型JSON");
    }
    static TrainingProbe.Row row(int index, double a, double b, ForecastDirection label) {
        double[] x = new double[20]; x[0] = a; x[1] = b;
        LocalDate date = LocalDate.of(2000, 1, 1).plusDays(index);
        return new TrainingProbe.Row(date, date.plusDays(1), x, label);
    }
    static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("非法20维输入未拒绝");
    }
}
