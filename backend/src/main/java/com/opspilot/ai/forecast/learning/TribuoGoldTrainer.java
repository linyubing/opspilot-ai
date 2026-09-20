package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.Prediction;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.classification.sgd.linear.LogisticRegressionTrainer;
import org.tribuo.impl.ArrayExample;
import org.tribuo.provenance.SimpleDataSourceProvenance;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用 Tribuo 训练可解释的黄金三分类逻辑回归模型。 */
@Component("tribuoGoldTrainer")
public class TribuoGoldTrainer implements GoldTrainer {
    public static final String VERSION = "logistic-scaled-v2";
    private final boolean standardize;

    public TribuoGoldTrainer() {
        this(true);
    }

    @Autowired
    public TribuoGoldTrainer(@Value("${opspilot.forecast.gold.logistic.standardize:false}") boolean standardize) {
        this.standardize = standardize;
    }

    @Override
    public String name() {
        return standardize ? VERSION : "logistic-v1";
    }

    @Override
    public GoldClassifier train(List<GoldSample> samples, Set<String> featureNames) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("训练样本不能为空");
        }
        if (featureNames == null || featureNames.isEmpty() || !GoldFeatures.NAMES.containsAll(featureNames)) {
            throw new IllegalArgumentException("模型特征不能为空且必须来自黄金特征集合");
        }
        List<String> sorted = featureNames.stream().sorted().toList();
        String[] names = sorted.toArray(String[]::new);
        // 每次滚动重训独立拟合；验证样本与留出样本不得参与统计。
        FeatureScaler scaler = standardize ? FeatureScaler.fit(samples, sorted) : null;

        LabelFactory factory = new LabelFactory();
        MutableDataset<Label> dataset = new MutableDataset<>(
                new SimpleDataSourceProvenance("gold-forecast-training", factory),
                factory
        );
        for (GoldSample sample : samples) {
            dataset.add(example(new Label(sample.label().name()), sample.features(), names, sorted, scaler));
        }

        Model<Label> model = new LogisticRegressionTrainer().train(dataset);
        return features -> probabilities(model.predict(
                example(LabelFactory.UNKNOWN_LABEL, features, names, sorted, scaler)
        ));
    }

    private ArrayExample<Label> example(
            Label label,
            GoldFeatures features,
            String[] names,
            List<String> sorted,
            FeatureScaler scaler
    ) {
        double[] values = scaler != null ? scaler.values(features) : sorted.stream()
                .mapToDouble(name -> features.values().get(name))
                .toArray();
        return new ArrayExample<>(label, names, values);
    }

    private DirectionProbabilities probabilities(Prediction<Label> prediction) {
        if (!prediction.hasProbabilities()) {
            throw new IllegalStateException("逻辑回归没有返回分类概率");
        }
        Map<String, Label> scores = prediction.getOutputScores();
        return new DirectionProbabilities(
                score(scores, ForecastDirection.BULLISH),
                score(scores, ForecastDirection.NEUTRAL),
                score(scores, ForecastDirection.BEARISH)
        );
    }

    private double score(
            Map<String, Label> scores,
            ForecastDirection direction
    ) {
        Label label = scores.get(direction.name());
        if (label == null) {
            throw new IllegalStateException("逻辑回归缺少方向概率：" + direction);
        }
        return label.getScore();
    }
}
