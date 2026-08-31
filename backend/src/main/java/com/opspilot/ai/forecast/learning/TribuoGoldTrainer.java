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

import java.util.List;
import java.util.Map;

/** 使用 Tribuo 训练可解释的黄金三分类逻辑回归模型。 */
@Component("tribuoGoldTrainer")
public class TribuoGoldTrainer implements GoldTrainer {

    private static final List<String> FEATURE_NAMES = GoldFeatures.NAMES.stream()
            .sorted()
            .toList();

    @Override
    public String name() {
        return "logistic-v1";
    }

    @Override
    public GoldClassifier train(List<GoldSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("训练样本不能为空");
        }

        LabelFactory factory = new LabelFactory();
        MutableDataset<Label> dataset = new MutableDataset<>(
                new SimpleDataSourceProvenance("gold-forecast-training", factory),
                factory
        );
        for (GoldSample sample : samples) {
            dataset.add(example(new Label(sample.label().name()), sample.features()));
        }

        Model<Label> model = new LogisticRegressionTrainer().train(dataset);
        return features -> probabilities(model.predict(
                example(LabelFactory.UNKNOWN_LABEL, features)
        ));
    }

    private ArrayExample<Label> example(
            Label label,
            GoldFeatures features
    ) {
        String[] names = FEATURE_NAMES.toArray(String[]::new);
        double[] values = FEATURE_NAMES.stream()
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
