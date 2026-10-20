package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 先核对逐日日期和标签，再评估完整样本及同信号日期基线，不重训模型。 */
public class PairedForecastEvaluator {
    public static final String VERSION = "gold-paired-evaluation-v1";
    private final ForecastEvaluator evaluator;

    public PairedForecastEvaluator(ForecastEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator);
    }

    public PairedMetrics evaluate(List<SettledPrediction> model, List<SettledPrediction> baseline) {
        if (model.isEmpty() || model.size() != baseline.size()) {
            throw new IllegalArgumentException("配对评估必须使用相同的非空日期集合");
        }
        var seen = new HashSet<java.time.LocalDate>();
        var signalDates = new ArrayList<java.time.LocalDate>();
        var selectedBaseline = new ArrayList<SettledPrediction>();
        for (int i = 0; i < model.size(); i++) {
            var row = model.get(i);
            var base = baseline.get(i);
            if (!seen.add(row.asOfDate()) || !row.asOfDate().equals(base.asOfDate())
                    || row.actual() != base.actual()) {
                throw new IllegalArgumentException("配对评估的日期重复、错位或真实标签不一致");
            }
            if (row.prediction().status() == SignalStatus.PREDICTED) {
                signalDates.add(row.asOfDate());
                selectedBaseline.add(full(base));
            }
        }
        return new PairedMetrics(evaluator.evaluate(model.stream().map(PairedForecastEvaluator::full).toList()),
                selectedBaseline.isEmpty() ? null : evaluator.evaluate(selectedBaseline), signalDates);
    }

    /** 仅供完整样本诊断，概率并列按上涨、中性、下跌固定顺序，不看真实标签。 */
    static SettledPrediction full(SettledPrediction row) {
        var p = row.probabilities();
        var direction = p.bullish() >= p.neutral() && p.bullish() >= p.bearish()
                ? ForecastDirection.BULLISH : p.neutral() >= p.bearish()
                ? ForecastDirection.NEUTRAL : ForecastDirection.BEARISH;
        double confidence = Math.max(p.bullish(), Math.max(p.neutral(), p.bearish()));
        return new SettledPrediction(row.asOfDate(), p,
                new GoldPrediction(SignalStatus.PREDICTED, direction, confidence), row.actual());
    }
}
