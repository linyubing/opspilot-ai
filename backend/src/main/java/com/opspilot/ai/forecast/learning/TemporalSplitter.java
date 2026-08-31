package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.backtest.BacktestDataInsufficientException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 按时间顺序切分样本，并用周期隔离防止标签泄漏。 */
@Component
public class TemporalSplitter {

    private static final int TRAINING_MIN = 500;
    private static final int VALIDATION_SIZE = 240;
    private static final int HOLDOUT_SIZE = 240;

    public TemporalDataset split(
            List<GoldSample> samples,
            ForecastHorizon horizon
    ) {
        Objects.requireNonNull(samples, "样本不能为空");
        Objects.requireNonNull(horizon, "预测周期不能为空");
        validateOrder(samples, horizon);

        int gap = horizon.sessions();
        int required = TRAINING_MIN + VALIDATION_SIZE
                + HOLDOUT_SIZE + 2 * gap;
        if (samples.size() < required) {
            throw new BacktestDataInsufficientException(
                    "完整样本不足，实际数量=" + samples.size()
                            + "，所需数量=" + required
            );
        }

        int holdoutStart = samples.size() - HOLDOUT_SIZE;
        int validationEnd = holdoutStart - gap;
        int validationStart = validationEnd - VALIDATION_SIZE;
        int trainingEnd = validationStart - gap;

        return new TemporalDataset(
                samples.subList(0, trainingEnd),
                samples.subList(validationStart, validationEnd),
                samples.subList(holdoutStart, samples.size())
        );
    }

    private void validateOrder(
            List<GoldSample> samples,
            ForecastHorizon horizon
    ) {
        for (int i = 0; i < samples.size(); i++) {
            GoldSample sample = Objects.requireNonNull(
                    samples.get(i), "样本项不能为空"
            );
            if (sample.horizon() != horizon) {
                throw new IllegalArgumentException("样本预测周期不一致");
            }
            if (i > 0 && !sample.asOfDate().isAfter(samples.get(i - 1).asOfDate())) {
                throw new IllegalArgumentException("样本必须按分析日期严格升序排列");
            }
        }
    }
}
