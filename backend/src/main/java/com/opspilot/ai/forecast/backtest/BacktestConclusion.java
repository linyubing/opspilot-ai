package com.opspilot.ai.forecast.backtest;

import java.math.BigDecimal;

/** 将多个回测指标归纳为可直接阅读的谨慎结论。 */
public record BacktestConclusion(
        ConclusionLevel level,
        String summary
) {

    private static final int MIN_SAMPLES = 30;

    // 至少超过多数类基线 5 个百分点，才认为优势值得继续验证。
    private static final BigDecimal MIN_LIFT =
            new BigDecimal("0.0500");

    // 三类方向的平均召回率至少达到 55%，才认为方向表现相对均衡。
    private static final BigDecimal MIN_BALANCED =
            new BigDecimal("0.5500");

    /**
     * 根据样本量、基线提升和平衡准确率生成评估结论。
     *
     * 判断顺序很重要：先检查样本量，再检查是否超过基线，
     * 最后检查方向平衡和优势大小。
     */
    public static BacktestConclusion from(BacktestEvaluation value) {
        if (value.sampleCount() < MIN_SAMPLES) {
            return new BacktestConclusion(
                    ConclusionLevel.INSUFFICIENT,
                    "有效样本不足 30 条，当前结果只能用于观察。"
            );
        }

        BigDecimal lift = value.accuracyLift();
        BigDecimal balanced = value.balancedAccuracy();

        // 指标为空表示当前数据不足以形成有效结论。
        if (lift == null || balanced == null) {
            return new BacktestConclusion(
                    ConclusionLevel.INSUFFICIENT,
                    "回测指标不完整，当前结果只能用于观察。"
            );
        }

        // compareTo 小于或等于 0，表示没有战胜多数类别基线。
        if (lift.compareTo(BigDecimal.ZERO) <= 0) {
            return new BacktestConclusion(
                    ConclusionLevel.NO_EDGE,
                    "模型没有超过多数类别基线，暂未发现预测优势。"
            );
        }

        // 平衡准确率过低，说明模型可能只擅长某一个方向。
        if (balanced.compareTo(MIN_BALANCED) < 0) {
            return new BacktestConclusion(
                    ConclusionLevel.UNBALANCED,
                    "模型对不同方向的识别不均衡，暂不具备稳定性。"
            );
        }

        if (lift.compareTo(MIN_LIFT) < 0) {
            return new BacktestConclusion(
                    ConclusionLevel.WEAK,
                    "模型略高于基线，但提升不足 5 个百分点。"
            );
        }

        return new BacktestConclusion(
                ConclusionLevel.PROMISING,
                "模型已超过基线且方向表现较均衡，值得继续扩大样本验证。"
        );
    }
}
