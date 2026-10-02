package com.opspilot.ai.forecast.learning;

import java.util.List;
import org.tribuo.Model;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.impl.ArrayExample;

/** 固定参数的独立20维树训练适配，不要求缺失的宏观字段。 */
final class TreeFit {
    static XgboostProperties parameters() {
        return new XgboostProperties(200, .03, 0, 3, 5, .8, .8, 1, 0, 1, 20260901L);
    }
    static Model<Label> train(List<TrainingProbe.Row> rows, TrainingProbe.Scale scale) {
        if (rows == null || rows.isEmpty() || scale == null) throw new IllegalArgumentException("训练样本和缩放不能为空");
        for (var row : rows) {
            if (row == null || row.x() == null || row.x().length != 20 || row.label() == null)
                throw new IllegalArgumentException("必须使用完整20维样本");
            for (double value : row.x()) if (!Double.isFinite(value)) throw new IllegalArgumentException("特征必须有限");
        }
        for (int j = 0; j < 20; j++) if (!Double.isFinite(scale.mean[j]) || !Double.isFinite(scale.std[j]) || scale.std[j] < 0)
            throw new IllegalArgumentException("训练折缩放非法");
        // 直接构造20维数据集，避免向36维接口伪填缺失宏观值。
        return new XgboostGoldTrainer(parameters()).buildTrainer().train(TrainingProbe.dataset(rows, scale));
    }
    static double[] probabilities(Model<Label> model, TrainingProbe.Row row, TrainingProbe.Scale scale) {
        var prediction = model.predict(new ArrayExample<>(LabelFactory.UNKNOWN_LABEL,
                TrainingProbe.NAMES.toArray(String[]::new), scale.values(row)));
        if (!prediction.hasProbabilities()) throw new IllegalStateException("树模型没有概率输出");
        double[] p = new double[3];
        for (int c = 0; c < 3; c++) {
            var label = prediction.getOutputScores().get(TrainingProbe.LABELS[c]);
            if (label == null) throw new IllegalStateException("树模型缺少三方向概率");
            p[c] = label.getScore();
        }
        // 原生float32输出原样留存；只按产品1e-6检查总和，不归一化改变原始概率。
        new DirectionProbabilities(p[0], p[1], p[2]);
        return p;
    }
}
