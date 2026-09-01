package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.backtest.BacktestDataInsufficientException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
        validateTargetDates(samples);

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

        List<GoldSample> training = samples.subList(0, trainingEnd);
        List<GoldSample> validation = samples.subList(validationStart, validationEnd);
        List<GoldSample> holdout = samples.subList(holdoutStart, samples.size());

        validateTrainingTargetsNotInValidation(training, validation, gap);

        return new TemporalDataset(training, validation, holdout);
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

    /** 验证每条样本的 targetDate > asOfDate（GoldSample 构造器已保证，此处双重确认） */
    private void validateTargetDates(List<GoldSample> samples) {
        for (GoldSample sample : samples) {
            if (!sample.targetDate().isAfter(sample.asOfDate())) {
                throw new IllegalArgumentException(
                        "目标日期必须晚于分析日期，asOfDate=" + sample.asOfDate()
                                + "，targetDate=" + sample.targetDate()
                );
            }
        }
    }

    /**
     * 验证训练集的 targetDate 不会泄漏到验证集。
     * 训练样本的 targetDate 必须早于验证集第一个样本的 asOfDate 减去 gap。
     */
    private void validateTrainingTargetsNotInValidation(
            List<GoldSample> training,
            List<GoldSample> validation,
            int gap
    ) {
        if (validation.isEmpty() || training.isEmpty()) {
            return;
        }
        LocalDate validationFirstAsOf = validation.get(0).asOfDate();
        for (GoldSample sample : training) {
            if (!sample.targetDate().isBefore(validationFirstAsOf)) {
                throw new BacktestDataInsufficientException(
                        "训练样本目标日期不得晚于验证集分析日期，"
                                + "targetDate=" + sample.targetDate()
                                + "，validationFirstAsOf=" + validationFirstAsOf
                );
            }
        }
    }
}
