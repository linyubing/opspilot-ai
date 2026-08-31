package com.opspilot.ai.forecast.learning;

import java.util.List;

/** 保存成功构建的样本和因真实数据不完整而拒绝的数量。 */
public record GoldDataset(List<GoldSample> samples, int skippedCount) {
    public GoldDataset {
        samples = List.copyOf(samples);
        if (skippedCount < 0) {
            throw new IllegalArgumentException("拒绝样本数量不能为负数");
        }
    }
}
