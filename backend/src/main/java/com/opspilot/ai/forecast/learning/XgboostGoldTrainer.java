package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.tribuo.MutableDataset;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.classification.evaluation.LabelEvaluation;
import org.tribuo.classification.xgboost.XGBoostClassificationTrainer;
import org.tribuo.impl.ArrayExample;
import org.tribuo.provenance.SimpleDataSourceProvenance;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用 Tribuo XGBoost 训练黄金三分类梯度提升树模型。 */
@Component("xgboostGoldTrainer")
public class XgboostGoldTrainer implements GoldTrainer {

    private final XgboostProperties properties;

    public XgboostGoldTrainer(XgboostProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "tribuo-xgboost";
    }

    @Override
    public GoldClassifier train(List<GoldSample> samples, Set<String> featureNames) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("训练样本不能为空");
        }
        List<String> sorted = featureNames.stream().sorted().toList();
        String[] names = sorted.toArray(String[]::new);

        LabelFactory factory = new LabelFactory();
        MutableDataset<Label> dataset = new MutableDataset<>(
                new SimpleDataSourceProvenance("gold-forecast-xgboost-training", factory),
                factory
        );
        for (GoldSample sample : samples) {
            dataset.add(example(new Label(sample.label().name()), sample.features(), names, sorted));
        }

        try {
            XGBoostClassificationTrainer trainer = buildTrainer();
            org.tribuo.Model<Label> model = trainer.train(dataset);
            return features -> probabilities(model.predict(
                    example(LabelFactory.UNKNOWN_LABEL, features, names, sorted)
            ));
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | ExceptionInInitializerError e) {
            throw new ModelUnavailableException(
                    "当前 Windows 环境无法加载 XGBoost 原生库，阶段8实验未运行。请检查系统架构和 Visual C++ 运行库。", e
            );
        }
    }

    XGBoostClassificationTrainer buildTrainer() {
        return new XGBoostClassificationTrainer(
                properties.numTrees(),
                properties.eta(),
                properties.gamma(),
                properties.maxDepth(),
                properties.minChildWeight(),
                properties.subsample(),
                properties.featureSubsample(),
                properties.lambda(),
                properties.alpha(),
                properties.nThread(),
                true,
                properties.seed()
        );
    }

    private ArrayExample<Label> example(
            Label label,
            GoldFeatures features,
            String[] names,
            List<String> sorted
    ) {
        double[] values = sorted.stream()
                .mapToDouble(name -> features.values().get(name))
                .toArray();
        return new ArrayExample<>(label, names, values);
    }

    private DirectionProbabilities probabilities(org.tribuo.Prediction<Label> prediction) {
        if (!prediction.hasProbabilities()) {
            throw new IllegalStateException("XGBoost 没有返回分类概率");
        }
        Map<String, Label> scores = prediction.getOutputScores();
        return new DirectionProbabilities(
                score(scores, ForecastDirection.BULLISH),
                score(scores, ForecastDirection.NEUTRAL),
                score(scores, ForecastDirection.BEARISH)
        );
    }

    private double score(Map<String, Label> scores, ForecastDirection direction) {
        Label label = scores.get(direction.name());
        if (label == null) {
            throw new IllegalStateException("XGBoost 缺少方向概率：" + direction);
        }
        return label.getScore();
    }
}
