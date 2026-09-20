package com.opspilot.ai.forecast.learning;

import java.util.HashSet;
import java.util.List;

/** 仅从本次训练样本拟合均值和标准差，并为该模型保存不可变的变换参数。 */
final class FeatureScaler {
    private final List<String> names;
    private final double[] means;
    private final double[] scales;

    private FeatureScaler(List<String> names, double[] means, double[] scales) {
        this.names = List.copyOf(names);
        this.means = means.clone();
        this.scales = scales.clone();
    }

    static FeatureScaler fit(List<GoldSample> samples, List<String> names) {
        if (samples == null || samples.isEmpty() || names == null || names.isEmpty()
                || new HashSet<>(names).size() != names.size() || !GoldFeatures.NAMES.containsAll(names)) {
            throw new IllegalArgumentException("标准化需要非空训练样本和有效且不重复的特征");
        }
        double[] means = new double[names.size()];
        double[] variance = new double[names.size()];
        int count = 0;
        for (GoldSample sample : samples) {
            count++;
            for (int i = 0; i < names.size(); i++) {
                double value = sample.features().values().get(names.get(i));
                // Welford 增量统计避免先求平方和产生较大的舍入误差。
                double delta = value - means[i];
                means[i] += delta / count;
                variance[i] += delta * (value - means[i]);
            }
        }
        double[] scales = new double[names.size()];
        for (int i = 0; i < names.size(); i++) {
            scales[i] = Math.sqrt(Math.max(0, variance[i] / count));
            if (!Double.isFinite(means[i]) || !Double.isFinite(scales[i])) {
                throw new IllegalArgumentException("特征数值过大，无法可靠标准化");
            }
        }
        return new FeatureScaler(names, means, scales);
    }

    double[] values(GoldFeatures features) {
        double[] values = new double[names.size()];
        for (int i = 0; i < names.size(); i++) {
            // 训练期无变化的列不包含可学习信息；预测期也不能凭未来变化创造权重。
            values[i] = scales[i] == 0 ? 0
                    : (features.values().get(names.get(i)) - means[i]) / scales[i];
            if (!Double.isFinite(values[i])) {
                throw new IllegalArgumentException("特征标准化结果不是有限数值");
            }
        }
        return values;
    }
}
